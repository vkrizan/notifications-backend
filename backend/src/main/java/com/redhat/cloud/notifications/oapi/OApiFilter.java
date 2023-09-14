package com.redhat.cloud.notifications.oapi;

import com.redhat.cloud.notifications.Constants;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class OApiFilter {

    public static final String NOTIFICATIONS = "notifications";
    public static final String INTEGRATIONS = "integrations";
    public static final String PRIVATE = "private";
    public static final String INTERNAL = "internal";

    static List<String> openApiOptions = List.of(INTEGRATIONS, NOTIFICATIONS, PRIVATE, INTERNAL);
    static String INTEGRATIONS_DESCRIPTION = "The API for Integrations provides endpoints that you can use to create and manage integrations between third-party applications and the Red Hat Hybrid Cloud Console.";
    static String NOTIFICATIONS_DESCRIPTION = "The API for Notifications provides endpoints that you can use to create and manage event notifications between third-party applications and the Red Hat Hybrid Cloud Console.";

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8085")
    Integer port;

    /**
     * The name of the security scheme that smallrye will create for basic authentications. The default value is taken
     * from
     * <a href="https://quarkus.io/guides/openapi-swaggerui#quarkus-smallrye-openapi_quarkus.smallrye-openapi.security-scheme-name">the quarkus docs</a>.
     */
    @ConfigProperty(name = "quarkus.smallrye-openapi.security-scheme-name", defaultValue = "SecurityScheme")
    String securitySchemeName;

    private WebClient client;

    @PostConstruct
    void initialize() {
        this.client = WebClient.create(vertx,
                new WebClientOptions().setDefaultHost("localhost").setDefaultPort(port));
    }

    public String serveOpenApi(String openApiOption, String version) {
        if (!openApiOptions.contains(openApiOption)) {
            throw new WebApplicationException("No openapi file for [" + openApiOption + "] found.", 404);
        }

        HttpResponse<Buffer> response = client.get("/openapi.json").sendAndAwait();
        return filterJson(response.bodyAsJsonObject(), openApiOption, version).encode();
    }

    private JsonObject filterJson(JsonObject oapiModelJson, String openApiOption, String version) {

        JsonObject root = new JsonObject();

        oapiModelJson.stream().forEach(entry -> {
            String key = entry.getKey();
            switch (key) {
                case "components":
                case "openapi":
                    // We just copy all of them even if they may only apply to one
                    root.put(key, entry.getValue());
                    break;
                case "tags":
                    JsonArray tags = (JsonArray) entry.getValue();
                    JsonArray filteredTags = new JsonArray(tags
                            .stream()
                            .filter(o -> !((JsonObject) o).getString("name").equals(PRIVATE))
                            .collect(Collectors.toList()));
                    if (filteredTags.size() > 0) {
                        root.put(key, filteredTags);
                    }
                    break;
                case "paths":
                    JsonObject pathObject2 = new JsonObject();
                    JsonObject pathsObjectIn = (JsonObject) entry.getValue();
                    pathsObjectIn.stream().forEach(pathEntry -> {
                        String path = pathEntry.getKey();

                        JsonObject pathValue = (JsonObject) pathEntry.getValue();
                        if (!path.endsWith("openapi.json")) { // Skip the openapi endpoint
                            JsonObject newPathValue = null;
                            String mangledPath = mangle(path, openApiOption, version);

                            if (mangledPath != null) {
                                if (PRIVATE.equals(openApiOption)) {
                                    newPathValue = filterPrivateOperation(pathValue, false);
                                    mangledPath = path;
                                } else if (INTERNAL.equals(openApiOption) && path.startsWith(Constants.API_INTERNAL)) {
                                    newPathValue = filterPrivateOperation(pathValue, true);
                                } else if (path.startsWith(buildPath(openApiOption, version))) {
                                    newPathValue = filterPrivateOperation(pathValue, true);
                                }

                                if (newPathValue != null) {
                                    pathObject2.put(mangledPath, newPathValue);
                                }
                            }

                            // Removes the "roles" from the default security scheme generated by smallrye.
                            this.removeSecuritySchemeRoles(pathValue);
                        }
                    });
                    root.put("paths", pathObject2);
                    break;
                case "info":
                case "servers":
                    // Nothing. We handle info and servers below.
                    break;
                default:
                    throw new IllegalStateException("Unknown OpenAPI top-level element " + key);
            }
        });

        if (root.getJsonObject("paths").isEmpty()) {
            throw new NotFoundException();
        }

        JsonObject info = new JsonObject()
                .put("version", version == null ? "v1.0" : version)
                .put("title", capitalize(openApiOption));

        // Add servers section
        JsonArray serversArray = new JsonArray();
        String infoDescription = capitalize(openApiOption);

        if (openApiOption.equals(NOTIFICATIONS)) {
            serversArray.add(createProdServer(NOTIFICATIONS, version));
            serversArray.add(createDevServer(NOTIFICATIONS, version));
            infoDescription = NOTIFICATIONS_DESCRIPTION;
        } else if (openApiOption.equals(INTEGRATIONS)) {
            serversArray.add(createProdServer(INTEGRATIONS, version));
            serversArray.add(createDevServer(INTEGRATIONS, version));
            infoDescription = INTEGRATIONS_DESCRIPTION;
        }

        // Add info section
        info.put("description", infoDescription);
        root.put("info", info);

        root.put("servers", serversArray);

        return root;
    }

    private String capitalize(String name) {
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }

    private JsonObject filterPrivateOperation(JsonObject pathObject, boolean removePrivate) {
        JsonObject newPathObject = new JsonObject();
        pathObject.stream().forEach(entry -> {
            String verb = entry.getKey();
            JsonObject operation = (JsonObject) entry.getValue();
            boolean isPrivate = operation.containsKey("tags") && operation.getJsonArray("tags").contains(PRIVATE);
            if (isPrivate != removePrivate) {
                newPathObject.put(verb, operation);
            }
        });

        if (newPathObject.size() == 0) {
            return null;
        }

        return newPathObject;
    }

    private JsonObject createProdServer(String openApiOption, String version) {
        JsonObject job = new JsonObject();
        job.put("url", "https://console.redhat.com/{basePath}");
        job.put("description", "Production Server");
        job.put("variables", new JsonObject()
                .put("basePath", new JsonObject()
                        .put("default", buildPath(openApiOption, version))));
        return job;
    }

    private JsonObject createDevServer(String openApiOption, String version) {

        JsonObject job = new JsonObject();
        job.put("url", "http://localhost:{port}/{basePath}");
        job.put("description", "Development Server");
        job.put("variables", new JsonObject()
                .put("basePath", new JsonObject()
                        .put("default", buildPath(openApiOption, version)))
                .put("port", new JsonObject()
                        .put("default", "8080")));
        return job;
    }

    String mangle(String in, String openApiOption, String version) {
        String out = filterConstantsIfPresent(in, openApiOption, version);

        if (out != null && out.isEmpty()) {
            out = "/";
        }

        return out;
    }

    private String filterConstantsIfPresent(String in, String what, String version) {
        String[] paths;

        // Private is a regular API that is hidden
        if (what.equals(PRIVATE)) {
            paths = new String[]{buildPath(INTEGRATIONS, version), buildPath(NOTIFICATIONS, version)};
        } else {
            paths = new String[]{buildPath(what, version)};
        }

        for (String path: paths) {
            if (in.startsWith(path)) {
                return in.substring(path.length());
            }
        }

        return null;
    }

    /**
     * <p>Removes the roles from the default "SecurityScheme" security scheme for each operation of the given path. This
     * security scheme and its roles are automatically generated by "smallrye" when the {@link jakarta.annotation.security.RolesAllowed}
     * annotation is used on a method.</p>
     * <p>This causes a problem because it also generates a "basic authentication" component, which is incompatible
     * with the roles provided. In turn, this causes Red Hat's API Designer to give a warning on this issue.</p>
     * <p>Check the following Jira issue for more details: <a href="https://issues.redhat.com/browse/NOTIF-753">NOTIF-753</a>.</p>
     * @param pathValue the path value to strip the "SecurityScheme" roles from.
     */
    private void removeSecuritySchemeRoles(JsonObject pathValue) {
        pathValue.stream().forEach(operation -> {
            // The operation value might get modified with the new "security" JSON Array, which is a filtered array
            // without the "SecurityScheme" value.
            JsonObject opValue = (JsonObject) operation.getValue();
            JsonArray security = opValue.getJsonArray("security");

            if (security != null) {
                // Loop through the security entries, and if the target security scheme is found, empty the array.
                for (var entry : security) {
                    JsonObject element = (JsonObject) entry;
                    if (element.containsKey(this.securitySchemeName)) {
                        element.put(this.securitySchemeName, new JsonArray());
                    }
                }
            }
        });
    }

    private String buildPath(String openApiOption, String version) {
        if (version == null) {
            // Using root for non versioned API (internal)
            return "/" + openApiOption;
        }
        return "/api/%s/%s".formatted(openApiOption, version);
    }
}
