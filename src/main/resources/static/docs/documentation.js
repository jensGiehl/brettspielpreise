window.ui = SwaggerUIBundle({
    url: new URL("../api/openapi.yaml", window.location.href).href,
    dom_id: "#swagger-ui",
    deepLinking: true,
    displayRequestDuration: true,
    docExpansion: "list",
    filter: true,
    validatorUrl: null,
    supportedSubmitMethods: ["get"],
    presets: [SwaggerUIBundle.presets.apis],
    layout: "BaseLayout"
});
