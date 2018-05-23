package org.ovirt.engine.core.utils.serialization.json;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.DeserializationContext;
import org.codehaus.jackson.map.JsonDeserializer;
import org.ovirt.engine.core.common.action.ActionType;

public class ActionTypeDeserializer extends JsonDeserializer<ActionType> {
    private static Map<String, ActionType> mappings = new HashMap<>();

    static {
        for (ActionType action : ActionType.values()) {
            mappings.put(action.name(), action);
        }
    }

    @Override
    public ActionType deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
        String jsonValue = jsonParser.getText();
        ActionType actionType = mappings.get(jsonValue);
        if (actionType == null) {
            actionType = ActionType.Unknown;
        }
        return actionType;
    }
}
