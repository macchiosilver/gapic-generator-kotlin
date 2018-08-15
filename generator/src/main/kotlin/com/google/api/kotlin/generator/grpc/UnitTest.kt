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

import com.google.api.kotlin.GeneratedSource
import com.google.api.kotlin.GeneratorContext
import com.google.api.kotlin.TestableFunSpec
import com.google.api.kotlin.config.FlattenedMethod
import com.google.api.kotlin.config.PagedResponse
import com.google.api.kotlin.config.PropertyPath
import com.google.api.kotlin.config.ProtobufTypeMapper
import com.google.api.kotlin.generator.AbstractGenerator
import com.google.api.kotlin.generator.ParameterInfo
import com.google.api.kotlin.generator.ProtoFieldInfo
import com.google.api.kotlin.generator.describeMap
import com.google.api.kotlin.generator.isLongRunningOperation
import com.google.api.kotlin.generator.isMap
import com.google.api.kotlin.generator.isRepeated
import com.google.api.kotlin.types.GrpcTypes
import com.google.protobuf.DescriptorProtos
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

/** Generates the unit tests for the client methods. */
internal interface UnitTest {

    fun generate(ctx: GeneratorContext, apiMethods: List<TestableFunSpec>): GeneratedSource?

    /**
     * Create a unit test for a unary method with variations for paging, flattening,
     * and long running operations.
     */
    fun createUnaryMethodUnitTest(
        ctx: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        methodName: String,
        parameters: List<ParameterInfo>,
        flatteningConfig: FlattenedMethod?,
        paging: PagedResponse?
    ): CodeBlock

    /**
     * Create a unit test for a client, server, or bi-directional streaming method
     * with variations for paging, flattening, and long running operations.
     */
    fun createStreamingMethodTest(
        ctx: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        methodName: String,
        parameters: List<ParameterInfo>,
        flatteningConfig: FlattenedMethod?
    ): CodeBlock

    companion object {
        const val FUN_GET_CLIENT = "getClient"

        const val MOCK_STREAM_STUB = "streamingStub"
        const val MOCK_FUTURE_STUB = "futureStub"
        const val MOCK_OPS_STUB = "operationsStub"
        const val MOCK_CHANNEL = "channel"
        const val MOCK_CALL_OPTS = "options"
    }
}

internal class UnitTestImpl(private val stubs: Stubs) : AbstractGenerator(), UnitTest {

    override fun generate(
        ctx: GeneratorContext,
        apiMethods: List<TestableFunSpec>
    ): GeneratedSource? {
        val name = "${ctx.className.simpleName}Test"
        val unitTestType = TypeSpec.classBuilder(name)

        // add props (mocks) that will be used by all test methods
        val mocks = mapOf(
            UnitTest.MOCK_STREAM_STUB to stubs.getStreamStubType(ctx),
            UnitTest.MOCK_FUTURE_STUB to stubs.getFutureStubType(ctx),
            UnitTest.MOCK_OPS_STUB to stubs.getOperationsStubType(ctx),
            UnitTest.MOCK_CHANNEL to GrpcTypes.ManagedChannel,
            UnitTest.MOCK_CALL_OPTS to GrpcTypes.Support.ClientCallOptions
        )
        for ((propName, type) in mocks) {
            unitTestType.addProperty(
                PropertySpec.builder(propName, type)
                    .initializer("mock()")
                    .build()
            )
        }

        // add a function to reset the mocks before each test
        unitTestType.addFunction(
            FunSpec.builder("resetMocks")
                .addAnnotation(ClassName("kotlin.test", "BeforeTest"))
                .addStatement(
                    "reset(%N, %N, %N, %N, %N)", *mocks.keys.toTypedArray()
                )
                .build()
        )

        // add a function to create a client for each test
        unitTestType.addFunction(
            FunSpec.builder(UnitTest.FUN_GET_CLIENT)
                .returns(ctx.className)
                .addStatement(
                    """
                        |return %T.fromStubs(object: %T.%L.Factory {
                        |    override fun create(channel: %T, options: %T) =
                        |        %T.%L(%N, %N, %N)
                        |}, %N, %N)
                        |""".trimMargin(),
                    ctx.className, ctx.className, Stubs.CLASS_STUBS,
                    GrpcTypes.ManagedChannel, GrpcTypes.Support.ClientCallOptions,
                    ctx.className, Stubs.CLASS_STUBS,
                    UnitTest.MOCK_STREAM_STUB, UnitTest.MOCK_FUTURE_STUB, UnitTest.MOCK_OPS_STUB,
                    UnitTest.MOCK_CHANNEL, UnitTest.MOCK_CALL_OPTS
                )
                .build()
        )

        // add all of the test methods for the API
        unitTestType.addFunctions(generateFunctions(apiMethods))

        // put it all together and add static imports
        return if (unitTestType.funSpecs.isNotEmpty()) {
            GeneratedSource(
                ctx.className.packageName,
                name,
                types = listOf(unitTestType.build()),
                imports = listOf(
                    ClassName("kotlin.test", "assertEquals"),
                    ClassName("kotlin.test", "assertNotNull"),
                    ClassName("com.nhaarman.mockito_kotlin", "reset"),
                    ClassName("com.nhaarman.mockito_kotlin", "whenever"),
                    ClassName("com.nhaarman.mockito_kotlin", "doReturn"),
                    ClassName("com.nhaarman.mockito_kotlin", "mock"),
                    ClassName("com.nhaarman.mockito_kotlin", "verify"),
                    ClassName("com.nhaarman.mockito_kotlin", "times"),
                    ClassName("com.nhaarman.mockito_kotlin", "check"),
                    ClassName("com.nhaarman.mockito_kotlin", "eq"),
                    ClassName("com.nhaarman.mockito_kotlin", "any")
                ),
                kind = GeneratedSource.Kind.UNIT_TEST
            )
        } else {
            null
        }
    }

    private fun generateFunctions(functions: List<TestableFunSpec>): List<FunSpec> {
        val nameCounter = mutableMapOf<String, Int>()

        return functions
            .filter { it.unitTestCode != null }
            .map {
                // add a numbered suffix to the name if there are overloads
                var name = "test${it.function.name.capitalize()}"
                val suffix = nameCounter[name] ?: 0
                nameCounter[name] = suffix + 1
                if (suffix > 0) {
                    name += suffix
                }

                // create fun!
                FunSpec.builder(name)
                    .addAnnotation(ClassName("kotlin.test", "Test"))
                    .addCode(it.unitTestCode!!)
                    .build()
            }
    }

    override fun createUnaryMethodUnitTest(
        ctx: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        methodName: String,
        parameters: List<ParameterInfo>,
        flatteningConfig: FlattenedMethod?,
        paging: PagedResponse?
    ): CodeBlock {
        val originalReturnType = ctx.typeMap.getKotlinType(method.outputType)
        val originalInputType = ctx.typeMap.getKotlinType(method.inputType)

        // create mocks for input params and the future returned by the stub
        val givenBlock = createGivenCodeBlock(ctx, parameters)
        givenBlock.code.add(
            """
               |val future: %T = mock()
               |whenever(%N.executeFuture<%T>(any())).thenReturn(future)
               |""".trimMargin(),
            GrpcTypes.Support.FutureCall(originalReturnType),
            UnitTest.MOCK_FUTURE_STUB, originalReturnType
        )

        // if paging add extra mocks for the page handling
        if (paging != null) {
            val pageSizeSetter = getSetterName(paging.pageSize)
            val nextPageTokenGetter = getAccessorName(paging.responsePageToken)
            val responseListGetter = getAccessorRepeatedName(paging.responseList)
            val responseListItemType = getResponseListElementType(ctx, method, paging)

            givenBlock.code.add(
                """
                    |
                    |val pageBodyMock: %T = mock {
                    |    on { %L } doReturn "token"
                    |    on { %L } doReturn mock<List<%T>>()
                    |}
                    |whenever(future.get()).thenReturn(%T(pageBodyMock, mock()))
                    |""".trimMargin(),
                originalReturnType,
                nextPageTokenGetter,
                responseListGetter, responseListItemType,
                GrpcTypes.Support.CallResult(originalReturnType)
            )

            // non-paged flattened methods need an extra mock since the original
            // request object is not directly used (it's builder is used instead)
            if (flatteningConfig == null) {
                val theRequest = givenBlock.variables.values.map { it.variableName }.first()
                givenBlock.code.add(
                    """
                        |val builder: %T.Builder = mock()
                        |whenever(%N.toBuilder()).thenReturn(builder)
                        |whenever(builder.%L(any())).thenReturn(builder)
                        |whenever(builder.build()).thenReturn(%N)
                        |""".trimMargin(),
                    originalInputType,
                    theRequest,
                    pageSizeSetter,
                    theRequest
                )
            }
        }

        // invoke client
        val expectedPageSize = 14
        val whenBlock =
            createWhenCodeBlock(givenBlock, methodName, parameters, paging, expectedPageSize)

        // verify the returned values
        val thenBlock = createThenCode("future", method, paging)
        val check =
            createStubCheckCode(givenBlock, ctx, method, flatteningConfig, paging, expectedPageSize)

        // verify the executeFuture occurred (and use input block to verify)
        thenBlock.code.add(
            """
                |verify(%N).executeFuture<%T>(check {
                |    val mock: %T = mock()
                |    it(mock)
                |    verify(mock).%N(%L)
                |})
                |""".trimMargin(),
            UnitTest.MOCK_FUTURE_STUB, originalReturnType,
            stubs.getFutureStubType(ctx).typeArguments.first(),
            methodName, check
        )

        // verify page size was set (for flattened methods this happens in the check block)
        if (paging != null && flatteningConfig == null) {
            thenBlock.code.addStatement(
                "verify(builder, times(2)).%L(eq($expectedPageSize))",
                getSetterName(paging.pageSize)
            )
        }

        // put it all together
        return createUnitTest(givenBlock, whenBlock, thenBlock)
    }

    override fun createStreamingMethodTest(
        ctx: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        methodName: String,
        parameters: List<ParameterInfo>,
        flatteningConfig: FlattenedMethod?
    ): CodeBlock {
        val originalReturnType = ctx.typeMap.getKotlinType(method.outputType)
        val originalInputType = ctx.typeMap.getKotlinType(method.inputType)

        // create mocks for input params and the future returned by the stub
        val givenBlock = createGivenCodeBlock(ctx, parameters)

        // determine method that will be used and it's return type
        val streamMethod = when {
            method.hasClientStreaming() && !method.hasServerStreaming() ->
                CodeBlock.of(
                    "executeClientStreaming<%T, %T>",
                    originalInputType, originalReturnType
                )
            method.hasServerStreaming() && !method.hasClientStreaming() ->
                CodeBlock.of(
                    "executeServerStreaming<%T>",
                    originalReturnType
                )
            else -> CodeBlock.of(
                "executeStreaming<%T, %T>",
                originalInputType, originalReturnType
            )
        }
        val streamMethodReturnType = when {
            method.hasClientStreaming() && !method.hasServerStreaming() ->
                GrpcTypes.Support.ClientStreamingCall(originalInputType, originalReturnType)
            method.hasServerStreaming() && !method.hasClientStreaming() ->
                GrpcTypes.Support.ServerStreamingCall(originalReturnType)
            else -> GrpcTypes.Support.StreamingCall(originalInputType, originalReturnType)
        }

        // add a mock for the streaming call
        // if flattened add a mock for the outbound request stream
        givenBlock.code.addStatement("val streaming: %T = mock()", streamMethodReturnType)
        if (flatteningConfig != null && method.hasClientStreaming()) {
            givenBlock.code.addStatement(
                "val streamingRequests: %T = mock()",
                GrpcTypes.Support.RequestStream(originalInputType)
            )
            givenBlock.code.addStatement("whenever(streaming.requests).thenReturn(streamingRequests)")
        }
        givenBlock.code.addStatement(
            "whenever(%N.%L(any())).thenReturn(streaming)",
            UnitTest.MOCK_STREAM_STUB,
            streamMethod
        )

        // invoke client
        val whenBlock = createWhenCodeBlock(givenBlock, methodName, parameters)

        // verify the returned values
        val thenBlock = createThenCode("streaming", method)

        // verify the executeFuture occurred (and use input block to verify)
        if (method.hasServerStreaming() && !method.hasClientStreaming()) {
            val check = createStubCheckCode(givenBlock, ctx, method, flatteningConfig)
            thenBlock.code.add(
                """
                    |verify(%N).%L(check {
                    |    val mock: %T = mock()
                    |    val mockObserver: %T = mock()
                    |    it(mock, mockObserver)
                    |    verify(mock).%L(%L, eq(mockObserver))
                    |})
                    |""".trimMargin(),
                UnitTest.MOCK_STREAM_STUB, streamMethod,
                stubs.getStreamStubType(ctx).typeArguments.first(),
                GrpcTypes.StreamObserver(originalReturnType),
                methodName, check
            )
        } else {
            thenBlock.code.add(
                """
                    |verify(%N).%L(check {
                    |    val mock: %T = mock()
                    |    assertEquals(mock::%L, it(mock))
                    |})
                    |""".trimMargin(),
                UnitTest.MOCK_STREAM_STUB, streamMethod,
                stubs.getStreamStubType(ctx).typeArguments.first(),
                methodName
            )
        }

        // if flattening was used also verify that the args were sent
        if (flatteningConfig != null && method.hasClientStreaming()) {
            val check = createStubCheckCode(givenBlock, ctx, method, flatteningConfig)
            thenBlock.code.add("verify(streamingRequests).send(%L)\n", check)
        }

        // put it all together
        return createUnitTest(givenBlock, whenBlock, thenBlock)
    }

    // common code for setting up mock inputs for unary and streaming methods
    private fun createGivenCodeBlock(
        ctx: GeneratorContext,
        parameters: List<ParameterInfo>
    ): GivenCodeBlock {
        // create a mock for each input parameter
        val variables = mapOf(*parameters.map {
            val valName = "the${it.spec.name.capitalize()}"
            val init = CodeBlock.of(
                "val $valName: %T = %L",
                it.spec.type,
                getMockValue(ctx.typeMap, it.flattenedFieldInfo)
            )
            Pair(it.spec.name, UnitTestVariable(valName, init))
        }.toTypedArray())

        // generate code for each of the given mocks
        val code = CodeBlock.builder()
        for ((_, value) in variables.entries) {
            code.addStatement("%L", value.initializer)
        }

        return GivenCodeBlock(variables, code)
    }

    // common code for invoking the client with mocked input parameters
    private fun createWhenCodeBlock(
        given: GivenCodeBlock,
        methodName: String,
        parameters: List<ParameterInfo>,
        paging: PagedResponse? = null,
        pageSize: Int? = null
    ): WhenCodeBlock {
        // invoke call to client using mocks
        val invokeClientParams = parameters.map {
            given.variables[it.spec.name]?.variableName
                ?: throw IllegalStateException("unable to determine variable name: ${it.spec.name}")
        }
        val testWhen = CodeBlock.builder()
            .addStatement("val client = %N()", UnitTest.FUN_GET_CLIENT)
        if (paging != null && pageSize != null) {
            testWhen.addStatement(
                "val result = client.%N(${invokeClientParams.joinToString(", ") { "%N" }}, $pageSize)",
                methodName,
                *invokeClientParams.toTypedArray()
            )
            testWhen.addStatement("val page = result.next()")
        } else {
            testWhen.addStatement(
                "val result = client.%N(${invokeClientParams.joinToString(", ") { "%N" }})",
                methodName,
                *invokeClientParams.toTypedArray()
            )
        }

        return WhenCodeBlock(testWhen)
    }

    // common code for verifying the result of the client call
    private fun createThenCode(
        mockName: String,
        method: DescriptorProtos.MethodDescriptorProto,
        paging: PagedResponse? = null
    ): ThenCodeBlock {
        val then = CodeBlock.builder()
        when {
            method.isLongRunningOperation() -> then.addStatement("assertNotNull(result)")
            paging != null -> then.addStatement("assertNotNull(page)")
            else -> then.addStatement("assertEquals($mockName, result)")
        }

        return ThenCodeBlock(then)
    }

    // common code for checking that a stub was invoked with correct params
    private fun createStubCheckCode(
        given: GivenCodeBlock,
        ctx: GeneratorContext,
        method: DescriptorProtos.MethodDescriptorProto,
        flatteningConfig: FlattenedMethod?,
        paging: PagedResponse? = null,
        expectedPageSize: Int? = null
    ): CodeBlock {
        val check = CodeBlock.builder()
        if (flatteningConfig == null) {
            check.add("eq(%N)", given.variables.values.map { it.variableName }.first())
        } else {
            val nestedAssert = mutableListOf<CodeBlock>()
            val paths = flatteningConfig.parameters
            visitFlattenedMethod(ctx, method, paths, object : Visitor() {
                override fun onTerminalParam(
                    currentPath: PropertyPath,
                    fieldInfo: ProtoFieldInfo
                ) {
                    val key = getAccessorName(currentPath.lastSegment)
                    val accessor = getAccessorCode(ctx.typeMap, fieldInfo)
                    val variable = given.variables[key]?.variableName
                        ?: throw IllegalStateException("Could not locate variable with name: $key")

                    nestedAssert.add(CodeBlock.of("assertEquals($variable, it$accessor)"))
                }
            })
            if (paging != null && expectedPageSize != null) {
                nestedAssert.add(
                    CodeBlock.of(
                        "assertEquals($expectedPageSize, it.${getAccessorName(
                            paging.pageSize
                        )})"
                    )
                )
            }
            check.add(
                """
                    |check {
                    |${nestedAssert.joinToString("\n") { "    %L" }}
                    |}""".trimMargin(), *nestedAssert.toTypedArray()
            )
        }

        return check.build()
    }

    // merge all the code blocks into a single block
    private fun createUnitTest(
        givenBlock: GivenCodeBlock,
        whenBlock: WhenCodeBlock,
        thenBlock: ThenCodeBlock
    ) =
        CodeBlock.of(
            """
                |%L
                |%L
                |%L""".trimMargin(),
            givenBlock.code.build(),
            whenBlock.code.build(),
            thenBlock.code.build()
        )

    // get a mock or primitive value for the given type
    private fun getMockValue(typeMap: ProtobufTypeMapper, type: ProtoFieldInfo?): String {
        // repeated fields or unknown use a mock
        if (type == null) {
            return "mock()"
        }

        // enums must use a real value
        if (typeMap.hasProtoEnumDescriptor(type.field.typeName)) {
            val descriptor = typeMap.getProtoEnumDescriptor(type.field.typeName)
            val kotlinType = typeMap.getKotlinType(type.field.typeName)
            val enum = descriptor.valueList.firstOrNull()?.name
                ?: throw IllegalStateException("unable to find default enum value for: ${type.field.typeName}")

            return "${kotlinType.simpleName}.$enum"
        }

        // primitives must use a real value
        // TODO: better / random values?
        fun getValue(t: DescriptorProtos.FieldDescriptorProto.Type) = when (t) {
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING -> "\"hi there!\""
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL -> "true"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_DOUBLE -> "2.0"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_FLOAT -> "4.0"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32 -> "2"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT32 -> "2"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED32 -> "2"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED32 -> "2"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT32 -> "2"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64 -> "400L"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT64 -> "400L"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED64 -> "400L"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED64 -> "400L"
            DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT64 -> "400L"
            else -> "mock()"
        }

        // use real lists and maps
        return when {
            type.field.isMap(typeMap) -> {
                val (keyType, valueType) = type.field.describeMap(typeMap)
                val k = getValue(keyType.type)
                val v = getValue(valueType.type)
                "mapOf($k to $v)"
            }
            type.field.isRepeated() -> "listOf(${getValue(type.field.type)})"
            else -> getValue(type.field.type)
        }
    }
}

private class GivenCodeBlock(
    val variables: Map<String, UnitTestVariable>,
    val code: CodeBlock.Builder
)

private class WhenCodeBlock(val code: CodeBlock.Builder)
private class ThenCodeBlock(val code: CodeBlock.Builder)
private class UnitTestVariable(val variableName: String, val initializer: CodeBlock)
