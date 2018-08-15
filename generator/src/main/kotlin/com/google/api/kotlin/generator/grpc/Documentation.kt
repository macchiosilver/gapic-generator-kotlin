/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.api.kotlin.generator.grpc

import com.google.api.kotlin.GeneratorContext
import com.google.api.kotlin.config.FlattenedMethod
import com.google.api.kotlin.config.PagedResponse
import com.google.api.kotlin.config.SampleMethod
import com.google.api.kotlin.generator.AbstractGenerator
import com.google.api.kotlin.generator.ParameterInfo
import com.google.api.kotlin.generator.getMethodComments
import com.google.api.kotlin.generator.getParameterComments
import com.google.api.kotlin.generator.isMessageType
import com.google.api.kotlin.generator.wrap
import com.google.protobuf.DescriptorProtos
import com.squareup.kotlinpoet.CodeBlock

/** Generates the KDoc documentation. */
internal interface Documentation {
    fun generateClassKDoc(ctx: GeneratorContext): CodeBlock
    fun generateMethodKDoc(
        ctx: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        methodName: String,
        samples: List<SampleMethod>,
        flatteningConfig: FlattenedMethod? = null,
        parameters: List<ParameterInfo> = listOf(),
        paging: PagedResponse? = null,
        extras: List<CodeBlock> = listOf()
    ): CodeBlock
}

internal class DocumentationImpl : AbstractGenerator(), Documentation {

    override fun generateClassKDoc(ctx: GeneratorContext): CodeBlock {
        val doc = CodeBlock.builder()
        val m = ctx.metadata

        // add primary (summary) section
        doc.add(
            """
                |%L
                |
                |%L
                |
                |[Product Documentation](%L)
                |""".trimMargin(),
            m.branding.name, m.branding.summary.wrap(), m.branding.url
        )

        // TODO: add other sections (quick start, etc.)

        return doc.build()
    }

    // create method comments from proto comments
    override fun generateMethodKDoc(
        ctx: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        methodName: String,
        samples: List<SampleMethod>,
        flatteningConfig: FlattenedMethod?,
        parameters: List<ParameterInfo>,
        paging: PagedResponse?,
        extras: List<CodeBlock>
    ): CodeBlock {
        val doc = CodeBlock.builder()

        // remove the spacing from proto files
        fun cleanupComment(text: String?) = text
            ?.replace("\\n\\s".toRegex(), "\n")
            ?.trim()

        // add proto comments
        val text = ctx.proto.getMethodComments(ctx.service, method)
        doc.add("%L\n\n", cleanupComment(text) ?: "")

        // add any samples
        if (samples.isEmpty()) {
            doc.add(generateMethodSample(ctx, method, methodName, null, flatteningConfig, paging))
        } else {
            for (sample in samples) {
                doc.add(
                    generateMethodSample(
                        ctx,
                        method,
                        methodName,
                        sample,
                        flatteningConfig,
                        paging
                    )
                )
            }
        }

        // add parameter comments
        val paramComments = flatteningConfig?.parameters?.mapIndexed { idx, path ->
            val fieldInfo = getProtoFieldInfoForPath(
                ctx, path, ctx.typeMap.getProtoTypeDescriptor(method.inputType)
            )
            val comment = fieldInfo.file.getParameterComments(fieldInfo)
            Pair(parameters[idx].spec.name, cleanupComment(comment))
        }?.filter { it.second != null } ?: listOf()
        paramComments.forEach { doc.add("\n@param %L %L\n", it.first, it.second) }

        // add any extra comments at the bottom (only used for the pageSize currently)
        extras.forEach { doc.add("\n%L\n", it) }

        // put it all together
        return doc.build()
    }

    private fun generateMethodSample(
        context: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        methodName: String,
        sample: SampleMethod?,
        flatteningConfig: FlattenedMethod?,
        paging: PagedResponse? = null
    ): CodeBlock {
        val call = CodeBlock.builder()

        // create client
        call.addStatement("For example:")
        call.addStatement("```")
        call.addStatement(
            "val client = %T.fromServiceAccount(YOUR_KEY_FILE)",
            context.className
        )

        if (methodName.toLowerCase() == "longRunningRecognize".toLowerCase()) {
            val x = 4
        }
        // create inputs
        val inputType = context.typeMap.getProtoTypeDescriptor(method.inputType)
        val invokeClientParams = if (flatteningConfig != null) {
            flatteningConfig.parameters.map { p ->
                val type = getProtoFieldInfoForPath(context, p, inputType)
                if (type.field.isMessageType()) {
                    getBuilder(context, type.message, type.kotlinType, listOf(p), sample).second
                } else {
                    CodeBlock.of(
                        "%L",
                        sample?.parameters?.find { it.parameterPath == p.toString() }?.value ?: p
                    )
                }
            }
        } else {
            val inputKotlinType = context.typeMap.getKotlinType(method.inputType)
            listOf(getBuilder(context, inputType, inputKotlinType, listOf(), sample).second)
        }

        // invoke method
        if (paging != null) {
            call.addStatement(
                "val resultList = client.%N(${invokeClientParams.joinToString(", ") { "%L" }})",
                methodName,
                *invokeClientParams.toTypedArray()
            )
            call.addStatement("val page = result.next()")
        } else {
            call.add(
                "val result = client.%N(${invokeClientParams.joinToString(", ") { "%L" }})\n",
                methodName,
                *invokeClientParams.toTypedArray()
            )
        }

        // close
        call.addStatement("```")

        return call.build()
    }
}
