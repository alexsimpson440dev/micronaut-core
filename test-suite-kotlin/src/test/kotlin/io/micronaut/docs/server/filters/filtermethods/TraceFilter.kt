/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.docs.server.filters.filtermethods

// tag::imports[]
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.RequestFilter
import io.micronaut.http.annotation.ResponseFilter
import io.micronaut.http.annotation.ServerFilter
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
// end::imports[]

@Requires(property = "spec.filter", value = "TraceFilterMethods")
// tag::clazz[]
@ServerFilter("/hello/**") // <1>
class TraceFilter(private val traceService: TraceService) { // <2>

    @RequestFilter
    @ExecuteOn(TaskExecutors.BLOCKING) // <3>
    fun filterRequest(request: HttpRequest<*>) {
        traceService.trace(request) // <4>
    }

    @ResponseFilter // <5>
    fun filterResponse(res: MutableHttpResponse<*>) {
        res.headers.add("X-Trace-Enabled", "true")
    }
}
// end::clazz[]
