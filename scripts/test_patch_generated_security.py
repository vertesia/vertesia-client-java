#!/usr/bin/env python3
"""Regression tests for patch_generated_security.py.

Run with: python3 -m unittest discover -s scripts -p 'test_*.py'
"""

from __future__ import annotations

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from patch_generated_security import patch_set_access_token  # noqa: E402

# Shape of the openapi-generator v7 okhttp-gson ApiClient before Spotless runs, when the spec has no OAuth
# security scheme (the client is generated for bearer authentication only).
WITHOUT_OAUTH = """    /**
     * Helper method to set access token for the first OAuth2 authentication.
     *
     * @param accessToken Access token
     */
    public void setAccessToken(String accessToken) {
        throw new RuntimeException("No OAuth2 authentication configured!");
    }
"""

# The same method when the spec has an OAuth security scheme.
WITH_OAUTH = """    /**
     * Helper method to set access token for the first OAuth2 authentication.
     *
     * @param accessToken Access token
     */
    public void setAccessToken(String accessToken) {
        for (Authentication auth : authentications.values()) {
            if (auth instanceof OAuth) {
                ((OAuth) auth).setAccessToken(accessToken);
                return;
            }
        }
        throw new RuntimeException("No OAuth2 authentication configured!");
    }
"""


class PatchSetAccessTokenTest(unittest.TestCase):
    def test_delegates_to_the_bearer_token_without_oauth(self) -> None:
        patched, changed = patch_set_access_token(WITHOUT_OAUTH)
        self.assertTrue(changed)
        self.assertIn("setBearerToken(accessToken);", patched)
        self.assertNotIn("No OAuth2 authentication configured!", patched)

    def test_is_idempotent(self) -> None:
        patched, _ = patch_set_access_token(WITHOUT_OAUTH)
        again, changed = patch_set_access_token(patched)
        self.assertFalse(changed)
        self.assertEqual(patched, again)

    def test_leaves_the_oauth_implementation_alone(self) -> None:
        patched, changed = patch_set_access_token(WITH_OAUTH)
        self.assertFalse(changed)
        self.assertEqual(WITH_OAUTH, patched)

    def test_fails_on_an_unknown_shape(self) -> None:
        with self.assertRaises(RuntimeError):
            patch_set_access_token("    public void setAccessToken(String accessToken) {\n        return;\n    }\n")


if __name__ == "__main__":
    unittest.main()
