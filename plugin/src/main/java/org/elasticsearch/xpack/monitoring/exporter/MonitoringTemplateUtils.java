/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.exporter;

import org.elasticsearch.Version;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.xpack.monitoring.MonitoredSystem;
import org.elasticsearch.xpack.template.TemplateUtils;
import org.joda.time.format.DateTimeFormatter;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class MonitoringTemplateUtils {

    private static final String TEMPLATE_FILE = "/monitoring-%s.json";
    private static final String TEMPLATE_VERSION_PROPERTY = Pattern.quote("${monitoring.template.version}");

    /**
     * The last version of X-Pack that updated the templates and pipelines.
     * <p>
     * It may be possible for this to diverge between templates and pipelines, but for now they're the same.
     */
    public static final int LAST_UPDATED_VERSION = Version.V_6_0_0_beta1.id;

    /**
     * Current version of templates used in their name to differentiate from breaking changes (separate from product version).
     */
    public static final String TEMPLATE_VERSION = "6";
    /**
     * The previous version of templates, which we still support via the REST _xpack/monitoring/_bulk endpoint because
     * nothing changed for those documents.
     */
    public static final String OLD_TEMPLATE_VERSION = "2";

    /**
     * IDs of templates that can be used with {@linkplain #loadTemplate(String) loadTemplate}.
     */
    public static final String[] TEMPLATE_IDS = { "alerts", "es", "kibana", "logstash", "beats" };

    /**
     * IDs of templates that can be used with {@linkplain #createEmptyTemplate(String) createEmptyTemplate} that are not managed by a
     * Resolver.
     * <p>
     * These should only be used by the HTTP Exporter to create old templates so that older versions can be properly upgraded. Older
     * instances will attempt to create a named template based on the templates that they expect (e.g., ".monitoring-es-2") and not the
     * ones that we are creating.
     */
    public static final String[] OLD_TEMPLATE_IDS = { "data", "es", "kibana", "logstash", "alerts" };

    /**
     * IDs of pipelines that can be used with
     */
    public static final String[] PIPELINE_IDS = { TEMPLATE_VERSION, OLD_TEMPLATE_VERSION };

    private MonitoringTemplateUtils() { }

    /**
     * Get a template name for any template ID.
     *
     * @param id The template identifier.
     * @return Never {@code null} {@link String} prefixed by ".monitoring-".
     * @see #TEMPLATE_IDS
     */
    public static String templateName(final String id) {
        return ".monitoring-" + id;
    }

    /**
     * Get a template name for any template ID for old templates in the previous version.
     *
     * @param id The template identifier.
     * @return Never {@code null} {@link String} prefixed by ".monitoring-" and ended by the {@code OLD_TEMPLATE_VERSION}.
     * @see #OLD_TEMPLATE_IDS
     */
    public static String oldTemplateName(final String id) {
        return ".monitoring-" + id + "-" + OLD_TEMPLATE_VERSION;
    }

    public static String loadTemplate(final String id) {
        String resource = String.format(Locale.ROOT, TEMPLATE_FILE, id);
        return TemplateUtils.loadTemplate(resource, TEMPLATE_VERSION, TEMPLATE_VERSION_PROPERTY);
    }

    /**
     * Create a template that does nothing but exist and provide a newer {@code version} so that we know that <em>we</em> created it.
     *
     * @param id The template identifier.
     * @return Never {@code null}.
     * @see #OLD_TEMPLATE_IDS
     * @see #OLD_TEMPLATE_VERSION
     */
    public static String createEmptyTemplate(final String id) {
        // e.g., { "index_patterns": [ ".monitoring-data-2*" ], "version": 6000002 }
        return "{\"index_patterns\":[\".monitoring-" + id + "-" + OLD_TEMPLATE_VERSION + "*\"],\"version\":" + LAST_UPDATED_VERSION + "}";
    }

    /**
     * Get a pipeline name for any template ID.
     *
     * @param id The template identifier.
     * @return Never {@code null} {@link String} prefixed by "xpack_monitoring_" and the {@code id}.
     * @see #TEMPLATE_IDS
     */
    public static String pipelineName(String id) {
        return "xpack_monitoring_" + id;
    }

    /**
     * Create a pipeline that allows documents for different template versions to be upgraded.
     * <p>
     * The expectation is that you will call either {@link XContentBuilder#string()} or {@link XContentBuilder#bytes()}}.
     *
     * @param id The API version (e.g., "2") to use
     * @param type The type of data you want to format for the request
     * @return Never {@code null}. Always an ended-object.
     * @throws IllegalArgumentException if {@code apiVersion} is unrecognized
     * @see #PIPELINE_IDS
     */
    public static XContentBuilder loadPipeline(final String id, final XContentType type) {
        switch (id) {
            case TEMPLATE_VERSION:
                return emptyPipeline(type);
            case OLD_TEMPLATE_VERSION:
                return pipelineForApiVersion2(type);
        }

        throw new IllegalArgumentException("unrecognized pipeline API version [" + id + "]");
    }

    /**
     * Create a pipeline to upgrade documents from {@link MonitoringTemplateUtils#OLD_TEMPLATE_VERSION}
     * <pre><code>
     * {
     *   "description" : "This pipeline upgrades documents ...",
     *   "version": 6000001,
     *   "processors": [ ]
     * }
     * </code></pre>
     * The expectation is that you will call either {@link XContentBuilder#string()} or {@link XContentBuilder#bytes()}}.
     *
     * @param type The type of data you want to format for the request
     * @return Never {@code null}. Always an ended-object.
     * @see #LAST_UPDATED_VERSION
     */
    static XContentBuilder pipelineForApiVersion2(final XContentType type) {
        try {
            // For now: We prepend the API version to the string so that it's easy to parse in the future; if we ever add metadata
            //  to pipelines, then it would better serve this use case
            return XContentBuilder.builder(type.xContent()).startObject()
                    .field("description", "This pipeline upgrades documents from the older version of the Monitoring API to " +
                                                "the newer version (" + TEMPLATE_VERSION + ") by fixing breaking " +
                                                "changes in those older documents before they are indexed from the older version (" +
                                                OLD_TEMPLATE_VERSION + ").")
                    .field("version", LAST_UPDATED_VERSION)
                    .startArray("processors")
                        .startObject()
                            // Drop the .monitoring-data-2 index and effectively drop unnecessary data (duplicate or simply unused)
                            .startObject("script")
                                .field("source",
                                       "boolean legacyIndex = ctx._index == '.monitoring-data-2';" +
                                       "if (legacyIndex || ctx._index.startsWith('.monitoring-es-2')) {" +
                                         "if (ctx._type == 'cluster_info') {" +
                                           "ctx._type = 'cluster_stats';" +
                                           "ctx._id = null;" +
                                         "} else if (legacyIndex || ctx._type == 'cluster_stats' || ctx._type == 'node') {" +
                                           "String index = ctx._index;" +
                                           "Object clusterUuid = ctx.cluster_uuid;" +
                                           "Object timestamp = ctx.timestamp;" +

                                           "ctx.clear();" +

                                           "ctx._id = 'xpack_monitoring_2_drop_bucket';" +
                                           "ctx._index = index;" +
                                           "ctx._type = 'legacy_data';" +
                                           "ctx.timestamp = timestamp;" +
                                           "ctx.cluster_uuid = clusterUuid;" +
                                         "}" +
                                         "if (legacyIndex) {" +
                                           "ctx._index = '<.monitoring-es-" + TEMPLATE_VERSION + "-{now}>';" +
                                         "}" +
                                       "}")
                            .endObject()
                        .endObject()
                        .startObject()
                            .startObject("rename")
                                .field("field", "_type")
                                .field("target_field", "type")
                            .endObject()
                        .endObject()
                        .startObject()
                            .startObject("set")
                                .field("field", "_type")
                                .field("value", "doc")
                            .endObject()
                        .endObject()
                        .startObject()
                            .startObject("gsub")
                                .field("field", "_index")
                                .field("pattern", "(.monitoring-\\w+-)2(-.+)")
                                .field("replacement", "$1" + TEMPLATE_VERSION + "$2")
                            .endObject()
                        .endObject()
                    .endArray()
                    .endObject();
        } catch (final IOException e) {
            throw new RuntimeException("Failed to create pipeline to upgrade from older version [" + OLD_TEMPLATE_VERSION +
                                       "] to the newer version [" + TEMPLATE_VERSION + "].", e);
        }
    }

    /**
     * Create an empty pipeline.
     * <pre><code>
     * {
     *   "description" : "This is a placeholder pipeline ...",
     *   "version": 6000001,
     *   "processors": [ ]
     * }
     * </code></pre>
     * The expectation is that you will call either {@link XContentBuilder#string()} or {@link XContentBuilder#bytes()}}.
     *
     * @param type The type of data you want to format for the request
     * @return Never {@code null}. Always an ended-object.
     * @see #LAST_UPDATED_VERSION
     */
    public static XContentBuilder emptyPipeline(final XContentType type) {
        try {
            // For now: We prepend the API version to the string so that it's easy to parse in the future; if we ever add metadata
            //  to pipelines, then it would better serve this use case
            return XContentBuilder.builder(type.xContent()).startObject()
                    .field("description", "This is a placeholder pipeline for Monitoring API version " + TEMPLATE_VERSION +
                                                " so that future versions may fix breaking changes.")
                    .field("version", LAST_UPDATED_VERSION)
                    .startArray("processors").endArray()
                    .endObject();
        } catch (final IOException e) {
            throw new RuntimeException("Failed to create empty pipeline", e);
        }
    }

    /**
     * Get the index name given a specific date format, a monitored system and a timestamp.
     *
     * @param formatter the {@link DateTimeFormatter} to use to compute the timestamped index name
     * @param system the {@link MonitoredSystem} for which the index name is computed
     * @param timestamp the timestamp value to use to compute the timestamped index name
     * @return the index name as a @{link String}
     */
    public static String indexName(final DateTimeFormatter formatter, final MonitoredSystem system, final long timestamp) {
        return ".monitoring-" + system.getSystem() + "-" + TEMPLATE_VERSION + "-" + formatter.print(timestamp);
    }
}
