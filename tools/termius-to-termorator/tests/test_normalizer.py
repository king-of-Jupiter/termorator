from types import SimpleNamespace
import unittest

from termorator_termius_export.normalizer import build_bundle


class NormalizerTest(unittest.TestCase):
    def test_nested_groups_inherited_auth_and_snippet_packages(self):
        key = SimpleNamespace(
            id="key-id/1",
            label="Production",
            private_key="PRIVATE",
            public_key="PUBLIC",
            passphrase="secret",
        )
        host = SimpleNamespace(
            id="host-id/10",
            alias="web",
            label="Web",
            address="192.0.2.10",
            port=22,
            username="",
            password="",
            group="Child",
            key=None,
            proxy=None,
            agent_forward=True,
            forwards=[],
        )
        model = SimpleNamespace(
            hosts=[host],
            keys=[key],
            source={"leveldb": "/tmp/Termius"},
        )
        decrypted = {
            "groups": [
                {"id": "g1", "label": "Root", "ssh_config": {"id": "c1"}},
                {"id": "g2", "label": "Child", "parent_group": {"id": "g1"}},
            ],
            "hosts": [
                {"id": "host-id", "local_id": 10, "group": {"id": "g2"}},
            ],
            "ssh_configs": [{"id": "c1", "port": 2222, "identity": {"id": "i1"}}],
            "ssh_identities": [
                {
                    "id": "i1",
                    "username": "root",
                    "password": "pw",
                    "ssh_key": {"id": "key-id"},
                }
            ],
            "keys": [{"id": "key-id", "local_id": 1}],
            "snippets_packages": [{"id": "p1", "label": "Deploy"}],
            "snippets": [
                {"id": "s1", "label": "Restart", "script": "systemctl restart app", "package": {"id": "p1"}}
            ],
        }

        bundle = build_bundle(model, decrypted, include_secrets=True, tool_commit="commit")

        self.assertEqual(bundle["hosts"][0]["group_path"], ["Root", "Child"])
        self.assertEqual(bundle["hosts"][0]["username"], "root")
        self.assertEqual(bundle["hosts"][0]["password"], "pw")
        self.assertEqual(bundle["hosts"][0]["port"], 2222)
        self.assertEqual(bundle["hosts"][0]["key_id"], "key-id/1")
        self.assertEqual(bundle["snippets"][0]["package_path"], ["Deploy"])

    def test_no_secrets_removes_private_material(self):
        key = SimpleNamespace(
            id="key/1", label="key", private_key="PRIVATE", public_key="PUBLIC", passphrase="secret"
        )
        host = SimpleNamespace(
            id="host/1",
            alias="host",
            label="host",
            address="example.com",
            port=22,
            username="root",
            password="password",
            group="",
            key=key,
            proxy=None,
            agent_forward=False,
            forwards=[],
        )
        model = SimpleNamespace(hosts=[host], keys=[key], source={})
        bundle = build_bundle(model, {"hosts": []}, include_secrets=False, tool_commit="commit")

        self.assertEqual(bundle["keys"][0]["private_key"], "")
        self.assertEqual(bundle["keys"][0]["passphrase"], "")
        self.assertEqual(bundle["hosts"][0]["password"], "")


if __name__ == "__main__":
    unittest.main()
