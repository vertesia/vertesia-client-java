#!/usr/bin/env python3
"""Regression tests for the scoped experimental routing Gson patch."""

from __future__ import annotations

import pathlib
import tempfile
import unittest
from unittest.mock import patch

import patch_routing_nullable_effort as routing


class PatchRoutingNullableEffortTest(unittest.TestCase):
    def test_optional_effort_tracks_builder_setter_and_json_presence_idempotently(self) -> None:
        source = """\
public class ExperimentalAgentRoutingControlChangeAnyOf {
  private ReasoningEffort effort;
  public ExperimentalAgentRoutingControlChangeAnyOf effort(ReasoningEffort effort) {
    this.effort = effort;
    return this;
  }
  public void setEffort(ReasoningEffort effort) {
    this.effort = effort;
  }
  /** Existing getter documentation. */
  @jakarta.annotation.Nullable
  public ReasoningEffort getEffort() {
    return effort;
  }
  void write() {
             JsonObject obj = thisAdapter.toJsonTree(value).getAsJsonObject();
             elementAdapter.write(out, obj);
  }
  void read() {
             return thisAdapter.fromJsonTree(jsonElement);
  }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "ExperimentalAgentRoutingControlChangeAnyOf.java"
            path.write_text(source)
            routing.patch_optional_effort(path)
            first = path.read_text()
            routing.patch_optional_effort(path)
            self.assertEqual(first, path.read_text())
        self.assertEqual(1, first.count(routing.MARKER))
        self.assertEqual(2, first.count("this.effortPresent = true;"))
        self.assertIn('obj.remove("effort");', first)
        self.assertIn('obj.add("effort", com.google.gson.JsonNull.INSTANCE);', first)
        self.assertIn('result.effortPresent = jsonElement.getAsJsonObject().has("effort");', first)
        self.assertLess(first.index("@jakarta.annotation.Nullable"), first.index("getEffort()"))
        self.assertLess(first.index("getEffort()"), first.index("hasEffort()"))

    def test_union_uses_leaf_adapter_and_exact_route_key_idempotently(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            models = pathlib.Path(directory)
            (models / "ModelBranch.java").write_text('SERIALIZED_NAME_MODEL = "model";')
            (models / "ProfileBranch.java").write_text(
                'SERIALIZED_NAME_INFERENCE_PROFILE = "inference_profile";'
            )
            path = models / "ExperimentalAgentRoutingControlChange.java"
            path.write_text("""\
TypeAdapter<ModelBranch> adapterModelBranch = gson.getDelegateAdapter(this, TypeToken.get(ModelBranch.class));
TypeAdapter<ProfileBranch> adapterProfileBranch = gson.getDelegateAdapter(this, TypeToken.get(ProfileBranch.class));
                    JsonElement jsonElement = elementAdapter.read(in);
""")
            with patch.object(routing, "MODELS", models):
                routing.patch_routing_union_adapters(path)
                first = path.read_text()
                routing.patch_routing_union_adapters(path)
                self.assertEqual(first, path.read_text())
        self.assertIn("gson.getAdapter(TypeToken.get(ModelBranch.class))", first)
        self.assertIn('routeFields.has("model") && routeFields.has("inference_profile")', first)
        self.assertIn("ModelBranch.validateJsonElement(jsonElement);", first)
        self.assertIn("ProfileBranch.validateJsonElement(jsonElement);", first)
        self.assertIn("adapterProfileBranch.fromJsonTree(jsonElement)", first)


if __name__ == "__main__":
    unittest.main()
