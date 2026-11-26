package com.jobfinder.service;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.Map;

/** Renders the HTML document templates with auto escaping. */
@Component
public class TemplateRenderer {

    private final PebbleEngine engine;

    public TemplateRenderer() {
        ClasspathLoader loader = new ClasspathLoader();
        loader.setPrefix("templates");
        this.engine = new PebbleEngine.Builder().loader(loader).newLineTrimming(false).build();
    }

    public String render(String templateName, Map<String, Object> model) {
        StringWriter output = new StringWriter();
        try {
            engine.getTemplate(templateName).evaluate(output, model);
        } catch (IOException error) {
            throw new UncheckedIOException("Unable to render " + templateName, error);
        }
        return output.toString();
    }
}
