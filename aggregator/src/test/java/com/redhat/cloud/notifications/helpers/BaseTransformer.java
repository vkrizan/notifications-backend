package com.redhat.cloud.notifications.helpers;

import com.redhat.cloud.notifications.ingress.Action;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
class BaseTransformer {

    JsonObject transform(Action action) {
        return toJsonObject(action);
    }

    private JsonObject toJsonObject(Action action) {
        JsonObject message = new JsonObject();
        message.put("bundle", action.getBundle());
        message.put("application", action.getApplication());
        message.put("event_type", action.getEventType());
        message.put("account_id", action.getAccountId());
        message.put("org_id", action.getOrgId());
        message.put("timestamp", action.getTimestamp().toString());
        message.put("events", new JsonArray(action.getEvents().stream().map(event -> Map.of(
                "metadata", JsonObject.mapFrom(event.getMetadata()),
                "payload", JsonObject.mapFrom(event.getPayload())
        )).collect(Collectors.toList())));
        message.put("context", JsonObject.mapFrom(action.getContext()));

        return message;
    }
}
