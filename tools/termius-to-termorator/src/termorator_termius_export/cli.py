from __future__ import annotations

import argparse
import json
import os
import pathlib
import sys

from termius_export import fsperm
from termius_export.crypto import DecryptionFailed, Decryptor, UnknownCipherVersion, first_ciphertext
from termius_export.datadir import candidates as data_dir_candidates
from termius_export.datadir import default_data_dir
from termius_export.localkey import LocalKeyNotFound, load_local_key, wrong_key_message
from termius_export.normalize import build_model
from termius_export.source import IndexedDbNotFound, locate_leveldb
from termius_export import source as termius_source

from .normalizer import build_bundle

UPSTREAM_COMMIT = "d1a34e9bbf1dcf63dbcb910cfc24d54460a287d0"
EXTRA_DATABASES = (
    "snippets",
    "scripts",
    "snippets_packages",
    "snippet_packages",
    "snippet_groups",
    "snippet_folders",
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="termorator-termius-export",
        description="Export Termius hosts, nested groups, SSH keys and snippets for Termorator.",
    )
    parser.add_argument(
        "--data-dir",
        help="Termius data directory; auto-detected by default",
    )
    parser.add_argument(
        "--local-key-file",
        help="file containing Termius localKey; the OS keyring is used by default",
    )
    parser.add_argument(
        "--out",
        default="termius-termorator-export",
        help="secure output directory (default: termius-termorator-export)",
    )
    parser.add_argument(
        "--no-secrets",
        action="store_true",
        help="omit passwords, private keys and key passphrases",
    )
    return parser


def _key_validator(tables):
    sample = first_ciphertext(tables.tables)
    if sample is None:
        return None

    def works(candidate: str) -> bool:
        try:
            Decryptor.from_base64(candidate).field(sample)
        except (ValueError, DecryptionFailed, UnknownCipherVersion):
            return False
        return True

    return works


def _read_tables(leveldb):
    original = termius_source.WANTED_DATABASES
    termius_source.WANTED_DATABASES = tuple(dict.fromkeys((*original, *EXTRA_DATABASES)))
    try:
        return termius_source.read_tables(leveldb)
    finally:
        termius_source.WANTED_DATABASES = original


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    os.umask(0o077)

    data_dir = args.data_dir or default_data_dir()
    if not data_dir:
        print("Termius data directory was not found. Pass --data-dir.", file=sys.stderr)
        print("Looked in:\n  " + "\n  ".join(data_dir_candidates()), file=sys.stderr)
        return 2

    try:
        leveldb = locate_leveldb(data_dir)
        tables = _read_tables(leveldb)
        key_b64, key_source = load_local_key(args.local_key_file, _key_validator(tables))

        model = build_model(
            tables,
            Decryptor.from_base64(key_b64),
            source_info={"leveldb": str(leveldb), "local_key_source": key_source},
        )
        decryptor = Decryptor.from_base64(key_b64)
        decrypted = {
            name: [decryptor.walk(row) for row in rows]
            for name, rows in tables.tables.items()
        }
        bundle = build_bundle(
            model,
            decrypted,
            include_secrets=not args.no_secrets,
            tool_commit=UPSTREAM_COMMIT,
        )
    except IndexedDbNotFound as error:
        print(error, file=sys.stderr)
        return 2
    except LocalKeyNotFound as error:
        print(error, file=sys.stderr)
        return 2
    except UnknownCipherVersion as error:
        print(f"Termius encryption format is unsupported: {error}", file=sys.stderr)
        return 3
    except DecryptionFailed as error:
        print(f"Termius data could not be decrypted: {error}", file=sys.stderr)
        print(wrong_key_message(key_source), file=sys.stderr)
        return 3
    except Exception as error:  # the LevelDB reader has platform-specific exception types
        print(f"Export failed: {error}", file=sys.stderr)
        print("Close Termius and retry; its IndexedDB may be locked while the app is running.", file=sys.stderr)
        return 1

    out_dir = pathlib.Path(args.out).expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    fsperm.secure_dir(out_dir)
    output = out_dir / "termorator-termius.json"
    fsperm.write_private(
        output,
        json.dumps(bundle, indent=2, ensure_ascii=False) + "\n",
        0o600,
    )

    print(f"Created:  {output}")
    print(f"Hosts:   {len(bundle['hosts'])}")
    print(f"Keys:    {len(bundle['keys'])}")
    print(f"Snippets:{len(bundle['snippets']):>4}")
    if not args.no_secrets:
        print("WARNING: the JSON contains plaintext passwords and private keys; delete it after import.")
    for warning in fsperm.warnings():
        print(f"WARNING: {warning}")
    return 0
