#!/usr/bin/env python3
"""Regression tests for patch_generated_security.py.

Run with: python3 -m unittest discover -s scripts -p 'test_*.py'
"""

from __future__ import annotations

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from patch_generated_security import patch_set_access_token, patch_set_bearer_token  # noqa: E402

# Shapes of the openapi-generator v7 okhttp-gson ApiClient before Spotless runs. Without an oauth2-typed scheme,
# setAccessToken only throws.
SET_ACCESS_TOKEN_WITHOUT_OAUTH = """    /**
     * Helper method to set access token for the first OAuth2 authentication.
     *
     * @param accessToken Access token
     */
    public void setAccessToken(String accessToken) {
        throw new RuntimeException("No OAuth2 authentication configured!");
    }
"""

SET_BEARER_TOKEN = """    public void setBearerToken(Supplier<String> tokenSupplier) {
        for (Authentication auth : authentications.values()) {
            if (auth instanceof HttpBearerAuth) {
                ((HttpBearerAuth) auth).setBearerToken(tokenSupplier);
                return;
            }
        }
        throw new RuntimeException("No Bearer authentication configured!");
    }
"""

# Shape with the spec's OAuth2 scheme generated as a bearer scheme (SET_BEARER_AUTH_FOR_NAME).
TWO_BEARER_SCHEMES = (
    """        authentications.put("bearerAuth", new HttpBearerAuth("bearer"));
        authentications.put("OAuth2", new HttpBearerAuth("bearer"));
"""
    + SET_BEARER_TOKEN
    + "\n"
    + SET_ACCESS_TOKEN_WITHOUT_OAUTH
)

# The same methods when the spec's OAuth2 scheme is generated as oauth2.
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
    def test_sets_the_oauth2_bearer_scheme(self) -> None:
        patched, changed = patch_set_access_token(TWO_BEARER_SCHEMES)
        self.assertTrue(changed)
        self.assertIn('Authentication auth = authentications.get("OAuth2");', patched)
        self.assertIn("((HttpBearerAuth) auth).setBearerToken(accessToken);", patched)

    def test_is_idempotent(self) -> None:
        patched, _ = patch_set_access_token(TWO_BEARER_SCHEMES)
        again, changed = patch_set_access_token(patched)
        self.assertFalse(changed)
        self.assertEqual(patched, again)

    def test_leaves_the_oauth_implementation_alone(self) -> None:
        patched, changed = patch_set_access_token(WITH_OAUTH)
        self.assertFalse(changed)
        self.assertEqual(WITH_OAUTH, patched)

    def test_fails_without_an_oauth2_bearer_scheme(self) -> None:
        # The OAuth2 scheme filtered out: setAccessToken would have nothing to set.
        with self.assertRaises(RuntimeError):
            patch_set_access_token(SET_ACCESS_TOKEN_WITHOUT_OAUTH)

    def test_fails_on_an_unknown_shape(self) -> None:
        with self.assertRaises(RuntimeError):
            patch_set_access_token("    public void setAccessToken(String accessToken) {\n        return;\n    }\n")


class PatchSetBearerTokenTest(unittest.TestCase):
    def test_sets_the_bearer_auth_scheme_by_name(self) -> None:
        patched, changed = patch_set_bearer_token(SET_BEARER_TOKEN)
        self.assertTrue(changed)
        self.assertIn('Authentication auth = authentications.get("bearerAuth");', patched)
        self.assertNotIn("authentications.values()", patched)

    def test_is_idempotent(self) -> None:
        patched, _ = patch_set_bearer_token(SET_BEARER_TOKEN)
        again, changed = patch_set_bearer_token(patched)
        self.assertFalse(changed)
        self.assertEqual(patched, again)

    def test_fails_on_an_unknown_shape(self) -> None:
        with self.assertRaises(RuntimeError):
            patch_set_bearer_token("    public void setBearerToken(Supplier<String> tokenSupplier) {\n    }\n")


if __name__ == "__main__":
    unittest.main()
