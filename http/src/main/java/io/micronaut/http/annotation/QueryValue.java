/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.annotation;

import io.micronaut.context.annotation.AliasFor;
import io.micronaut.core.bind.annotation.Bindable;

import java.lang.annotation.*;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Indicates that the parameter to a method should be bound from a value in the query string or path of the URI.
 *
 * @author Graeme Rocher
 * @see java.net.URI#getQuery()
 * @see java.net.URI#getPath()
 * @since 1.0
 */
@Documented
@Retention(RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Bindable
@Inherited
public @interface QueryValue {

    /**
     * @return The name of the parameter
     */
    @AliasFor(annotation = Bindable.class, member = "value")
    String value() default "";

    /**
     * @see Bindable#defaultValue()
     * @return The default value
     */
    @AliasFor(annotation = Bindable.class, member = "defaultValue")
    String defaultValue() default "";

    /**
     * @return The format of the given values in the URL
     */
    Format format() default Format.URI_TEMPLATE_FORMAT;

    /**
     * The possible formats of the query parameter in the URL.
     * The conversion of various types is according to openapi v3 specification:
     * @see <a href="https://swagger.io/specification/">openapi v3 specification</a>
     * Values are serialized using Jackson object mapper
     */
    public static enum Format {
        /**
         * The format is taken from the URI template. This was the default behavior before 3.0.0.
         * For example, @Get("path{?param}") will be comma-delimited for Iterables and convert other values to String,
         * but @Get("path{?param*}") will be same as MULTI format for Iterables and expand the values of a Map as
         * separate parameters.
         */
        URI_TEMPLATE_FORMAT,
        /**
         * The values of iterator are comma-delimited.
         * Ambiguity can arise if values of Iterator contain commas inside themselves. In such case, the MUTLI format
         * should be preferred.
         * Null values are not supported and will be removed during the conversion process.
         */
        CSV,
        /**
         * The values are space-delimited, similarly to comma-delimited format.
         */
        SSV,
        /**
         * The values a delimited by pipes "|", similarly to comma-delimited format.
         */
        PIPES,
        /**
         * The values are repeated as separate parameters, e.g.: color=blue&color=black&color=brown.
         */
        MULTI,
        /**
         * The format supports 1-depth recursion into objects with setting each attribute as a separate parameter, e.g.:
         * 'color[R]=100&color[G]=200&color[B]=150'.
         */
        DEEP_OBJECT
    }
}
