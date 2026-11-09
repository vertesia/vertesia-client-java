package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertesia.model.NodeDefinition;
import io.vertesia.model.ProcessNodeType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves generated Java clients preserve process-agent skill activation and execution exclusions. */
class ProcessAgentLaunchPolicyDeserializationTest {

    @Test
    void deserializesAgentLaunchPolicy() {
        String body =
                """
                {
                  "type": "agent",
                  "interaction": "sys:AppDeveloper",
                  "inherit_context": false,
                  "initial_skills": ["app_quick_fix"],
                  "excluded_tools": ["learn_app_development"],
                  "agent_policy": {
                    "phases": [{
                      "id": "source_typechecked",
                      "tools": ["app_workspace_typecheck"],
                      "recovery_tools": ["app_workspace_read", "app_workspace_edit"],
                      "recovery_prompt": "Repair only after a concrete typecheck failure."
                    }],
                    "restrict_to_phase_tools": true
                  }
                }
                """;

        NodeDefinition node = JSON.getGson().fromJson(body, NodeDefinition.class);

        assertEquals(ProcessNodeType.AGENT, node.getType());
        assertEquals(false, node.getInheritContext());
        assertEquals(List.of("app_quick_fix"), node.getInitialSkills());
        assertEquals(List.of("learn_app_development"), node.getExcludedTools());
        assertEquals(
                List.of("app_workspace_read", "app_workspace_edit"),
                node.getAgentPolicy().getPhases().get(0).getRecoveryTools());
        assertEquals(true, node.getAgentPolicy().getRestrictToPhaseTools());
    }
}
