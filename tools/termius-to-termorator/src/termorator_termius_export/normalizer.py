"""Build the stable JSON contract consumed by Termorator.

The Termius storage reader, field decryption and base host/key normalization come from the
MIT-licensed y01and3/termius-export project. This module adds the target-specific shape,
nested group paths and snippets without depending on its private output files.
"""

from __future__ import annotations

import dataclasses

FORMAT = "termorator-termius-migration"
VERSION = 1


def _entity_key(row: dict | None) -> str:
    if not row:
        return ""
    return f"{row.get('id', '')}/{row.get('local_id', '')}"


def _index(rows: list[dict]) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for row in rows:
        if row.get("id") is not None:
            result[f"id:{row['id']}"] = row
        if row.get("local_id") is not None:
            result[f"lid:{row['local_id']}"] = row
    return result


def _deref(reference, index: dict[str, dict]) -> dict | None:
    if not reference:
        return None
    if isinstance(reference, dict):
        if reference.get("id") is not None:
            found = index.get(f"id:{reference['id']}")
            if found is not None:
                return found
        if reference.get("local_id") is not None:
            return index.get(f"lid:{reference['local_id']}")
        return None
    return index.get(f"id:{reference}") or index.get(f"lid:{reference}")


def _text(value) -> str:
    return value.strip() if isinstance(value, str) else ("" if value is None else str(value))


def _first(row: dict, *names: str):
    for name in names:
        value = row.get(name)
        if value is not None:
            return value
    return None


def _is_deleted(row: dict) -> bool:
    status = _text(row.get("status")).upper()
    return bool(row.get("deleted") or row.get("is_deleted") or status.startswith("DELETE"))


def _parents(row: dict | None, index: dict[str, dict], fields: tuple[str, ...]) -> list[dict]:
    """Return root-to-leaf ancestors, stopping safely on malformed cycles."""
    chain: list[dict] = []
    seen: set[str] = set()
    current = row
    while current:
        key = _entity_key(current)
        if key in seen:
            break
        seen.add(key)
        chain.append(current)
        current = _deref(_first(current, *fields), index)
    chain.reverse()
    return chain


def _labels(rows: list[dict]) -> list[str]:
    return [label for row in rows if (label := _text(_first(row, "label", "name", "title")))]


def _group_chain(host_row: dict, group_index: dict[str, dict]) -> list[dict]:
    group = _deref(_first(host_row, "group", "parent_group"), group_index)
    return _parents(group, group_index, ("parent_group", "parent", "group"))


def _effective_auth(
    host_row: dict,
    groups: list[dict],
    config_index: dict[str, dict],
    identity_index: dict[str, dict],
    key_index: dict[str, dict],
) -> dict:
    configs: list[dict] = []
    for owner in [*groups, host_row]:
        config = _deref(_first(owner, "ssh_config", "config"), config_index)
        if config:
            configs.append(config)

    result = {"username": "", "password": "", "key_id": None, "port": None}
    for config in configs:
        try:
            port = int(config.get("port"))
        except (TypeError, ValueError):
            port = 0
        if port > 0:
            result["port"] = port

        identity = _deref(_first(config, "identity"), identity_index)
        if not identity:
            continue
        username = _text(identity.get("username"))
        password = _text(identity.get("password"))
        key_row = _deref(_first(identity, "ssh_key", "key"), key_index)
        if username:
            result["username"] = username
        if password:
            result["password"] = password
        if key_row:
            result["key_id"] = _entity_key(key_row)
    return result


def _proxy_payload(proxy, include_secrets: bool) -> dict | None:
    if proxy is None:
        return None
    payload = dataclasses.asdict(proxy) if dataclasses.is_dataclass(proxy) else dict(vars(proxy))
    if not include_secrets:
        payload["password"] = ""
    return payload


def _forward_payload(forward) -> dict:
    payload = dataclasses.asdict(forward) if dataclasses.is_dataclass(forward) else dict(vars(forward))
    return {
        "label": _text(payload.get("label")),
        "type": _text(payload.get("type")) or "Local",
        "bound_address": _text(payload.get("bound_address")) or "127.0.0.1",
        "local_port": int(payload.get("local_port") or 0),
        "target_host": _text(payload.get("target_host")),
        "target_port": int(payload.get("target_port") or 0),
    }


def _snippet_rows(decrypted: dict[str, list[dict]]) -> list[dict]:
    rows: list[dict] = []
    for table in ("snippets", "scripts"):
        rows.extend(decrypted.get(table, []))
    return rows


def _package_rows(decrypted: dict[str, list[dict]]) -> list[dict]:
    rows: list[dict] = []
    for table in ("snippets_packages", "snippet_packages", "snippet_groups", "snippet_folders"):
        rows.extend(decrypted.get(table, []))
    return rows


def build_bundle(model, decrypted: dict[str, list[dict]], *, include_secrets: bool, tool_commit: str) -> dict:
    group_index = _index(decrypted.get("groups", []))
    host_rows = {_entity_key(row): row for row in decrypted.get("hosts", [])}
    config_index = _index(decrypted.get("ssh_configs", []))
    identity_index = _index(decrypted.get("ssh_identities", []))
    key_index = _index(decrypted.get("keys", []))
    active_key_ids = {
        _entity_key(row)
        for row in decrypted.get("keys", [])
        if not _is_deleted(row)
    }
    model_keys = {
        key.id: key
        for key in model.keys
        if not active_key_ids or key.id in active_key_ids
    }

    keys = [
        {
            "id": key.id,
            "label": key.label,
            "private_key": key.private_key if include_secrets else "",
            "public_key": key.public_key,
            "passphrase": key.passphrase if include_secrets else "",
        }
        for key in model_keys.values()
    ]

    hosts = []
    for host in model.hosts:
        source_row = host_rows.get(host.id, {})
        if source_row and _is_deleted(source_row):
            continue
        group_chain = _group_chain(source_row, group_index) if source_row else []
        effective = _effective_auth(source_row, group_chain, config_index, identity_index, key_index)
        key_id = effective["key_id"] or (host.key.id if host.key else None)
        if key_id not in model_keys:
            key_id = None

        hosts.append(
            {
                "id": host.id,
                "label": host.label or host.alias,
                "address": host.address,
                "port": effective["port"] or host.port or 22,
                "username": effective["username"] or host.username,
                "password": (effective["password"] or host.password) if include_secrets else "",
                "group_path": _labels(group_chain) or ([host.group] if host.group else []),
                "key_id": key_id,
                "proxy": _proxy_payload(host.proxy, include_secrets),
                "agent_forward": bool(host.agent_forward),
                "forwards": [_forward_payload(forward) for forward in host.forwards],
            }
        )

    package_rows = _package_rows(decrypted)
    package_index = _index(package_rows)
    snippets = []
    for row in _snippet_rows(decrypted):
        if _is_deleted(row):
            continue
        command = _text(_first(row, "script", "command", "snippet", "body", "content"))
        label = _text(_first(row, "label", "name", "title"))
        if not command:
            continue
        package = _deref(
            _first(row, "snippet_package", "package", "snippet_group", "group", "folder"),
            package_index,
        )
        package_chain = _parents(
            package,
            package_index,
            ("parent_package", "parent_group", "parent", "package", "group"),
        )
        snippets.append(
            {
                "id": _entity_key(row),
                "label": label or f"Snippet {len(snippets) + 1}",
                "command": command,
                "package_path": _labels(package_chain),
            }
        )

    return {
        "format": FORMAT,
        "version": VERSION,
        "source": {
            "app": "Termius",
            "tool": "https://github.com/y01and3/termius-export",
            "tool_commit": tool_commit,
            "leveldb": str(model.source.get("leveldb", "")),
        },
        "hosts": hosts,
        "keys": keys,
        "snippets": snippets,
    }
