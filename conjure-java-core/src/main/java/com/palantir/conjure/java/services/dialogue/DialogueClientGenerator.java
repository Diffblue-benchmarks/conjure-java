/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.conjure.java.services.dialogue;

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.palantir.conjure.java.ConjureAnnotations;
import com.palantir.conjure.java.services.ServiceGenerator;
import com.palantir.conjure.java.types.TypeMapper;
import com.palantir.conjure.spec.ArgumentDefinition;
import com.palantir.conjure.spec.AuthType;
import com.palantir.conjure.spec.BodyParameterType;
import com.palantir.conjure.spec.ConjureDefinition;
import com.palantir.conjure.spec.EndpointDefinition;
import com.palantir.conjure.spec.EndpointName;
import com.palantir.conjure.spec.HeaderParameterType;
import com.palantir.conjure.spec.HttpPath;
import com.palantir.conjure.spec.ListType;
import com.palantir.conjure.spec.MapType;
import com.palantir.conjure.spec.OptionalType;
import com.palantir.conjure.spec.ParameterType;
import com.palantir.conjure.spec.PathParameterType;
import com.palantir.conjure.spec.PrimitiveType;
import com.palantir.conjure.spec.QueryParameterType;
import com.palantir.conjure.spec.ServiceDefinition;
import com.palantir.conjure.spec.SetType;
import com.palantir.conjure.spec.Type;
import com.palantir.conjure.visitor.AuthTypeVisitor;
import com.palantir.conjure.visitor.ParameterTypeVisitor;
import com.palantir.conjure.visitor.TypeVisitor;
import com.palantir.dialogue.Call;
import com.palantir.dialogue.Calls;
import com.palantir.dialogue.Channel;
import com.palantir.dialogue.Deserializer;
import com.palantir.dialogue.Deserializers;
import com.palantir.dialogue.DialogueOkHttpErrorDecoder;
import com.palantir.dialogue.EmptyBody;
import com.palantir.dialogue.Endpoint;
import com.palantir.dialogue.Exceptions;
import com.palantir.dialogue.HttpMethod;
import com.palantir.dialogue.OkHttpErrorDecoder;
import com.palantir.dialogue.PathTemplate;
import com.palantir.dialogue.Request;
import com.palantir.dialogue.Serializer;
import com.palantir.dialogue.Serializers;
import com.palantir.tokens.auth.AuthHeader;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.uri.internal.UriTemplateParser;

// TODO(rfink): Add unit tests for misc edge cases, e.g.: docs/no-docs, auth/no-auth, binary return type.
public final class DialogueClientGenerator implements ServiceGenerator {

    private static final Class<?> EMPTY_BODY_CLASS = EmptyBody.class;
    private static final TypeName EMPTY_BODY = TypeName.get(EMPTY_BODY_CLASS);

    @Override
    public Set<JavaFile> generate(ConjureDefinition conjureDefinition) {
        TypeMapper parameterTypes = new TypeMapper(conjureDefinition.getTypes(),
                new ClassVisitor(conjureDefinition.getTypes(), ClassVisitor.Mode.PARAMETER));
        TypeMapper returnTypes = new TypeMapper(conjureDefinition.getTypes(),
                new ClassVisitor(conjureDefinition.getTypes(), ClassVisitor.Mode.RETURN_VALUE));
        TypeAwareGenerator generator = new TypeAwareGenerator(parameterTypes, returnTypes);
        return conjureDefinition.getServices().stream()
                .flatMap(serviceDef -> generator.service(serviceDef).stream())
                .collect(Collectors.toSet());
    }

    // TODO(rfink): Split into separate classes: endpoint, interface, impl.
    private static final class TypeAwareGenerator {
        private static final String AUTH_HEADER_NAME = "Authorization";
        private static final String AUTH_HEADER_PARAM_NAME = "authHeader";
        private static final String MAPPER_FIELD_NAME = "mapper";
        private static final String REQUEST_VAR_NAME = "_request";
        private static final String REQUEST_VAR_NAME_BUILDER = "_request_builder";

        private final TypeMapper parameterTypes;
        private final TypeMapper returnTypes;

        private TypeAwareGenerator(TypeMapper parameterTypes, TypeMapper returnTypes) {
            this.parameterTypes = parameterTypes;
            this.returnTypes = returnTypes;
        }

        private Set<JavaFile> service(ServiceDefinition def) {
            return ImmutableSet.of(blockingApi(def), client(def));
        }

        private JavaFile blockingApi(ServiceDefinition def) {
            TypeSpec.Builder serviceBuilder = TypeSpec.interfaceBuilder(serviceClassName("Blocking", def))
                    .addModifiers(Modifier.PUBLIC)
                    .addAnnotation(ConjureAnnotations.getConjureGeneratedAnnotation(DialogueClientGenerator.class));

            def.getDocs().ifPresent(docs ->
                    serviceBuilder.addJavadoc("$L", StringUtils.appendIfMissing(docs.get(), "\n")));

            serviceBuilder.addMethods(def.getEndpoints().stream()
                    .map(this::blockingApiMethod)
                    .collect(Collectors.toList()));

            return JavaFile.builder(def.getServiceName().getPackage(), serviceBuilder.build()).build();
        }

        private MethodSpec blockingApiMethod(EndpointDefinition endpointDef) {
            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(endpointDef.getEndpointName().get())
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .addParameters(methodParams(endpointDef));

            endpointDef.getDeprecated().ifPresent(deprecatedDocsValue -> methodBuilder.addAnnotation(Deprecated.class));
            ServiceGenerator.getJavaDoc(endpointDef).ifPresent(content -> methodBuilder.addJavadoc("$L", content));
            endpointDef.getReturns().ifPresent(type -> methodBuilder.returns(returnTypes.getClassName(type)));

            return methodBuilder.build();
        }

        private JavaFile client(ServiceDefinition def) {
            ClassName className = serviceClassName("Dialogue", def);
            TypeSpec.Builder serviceBuilder = TypeSpec.classBuilder(className)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addAnnotation(ConjureAnnotations.getConjureGeneratedAnnotation(DialogueClientGenerator.class));

            serviceBuilder.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
            serviceBuilder.addMethod(blockingClient(def));
            serviceBuilder.addField(FieldSpec.builder(ObjectMapper.class, "mapper")
                    .initializer("$T.newClientObjectMapper()",
                            // TODO(rfink): Stop relying on http-remoting.
                            ClassName.get("com.palantir.conjure.java.serialization", "ObjectMappers"))
                    .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .build());
            serviceBuilder.addFields(def.getEndpoints().stream()
                    .map(endpoint -> endpoint(endpoint, className))
                    .collect(Collectors.toList()));

            return JavaFile.builder(def.getServiceName().getPackage(), serviceBuilder.build()).build();
        }

        private static boolean requireBody(com.palantir.conjure.spec.HttpMethod httpMethod) {
            return !httpMethod.equals(com.palantir.conjure.spec.HttpMethod.GET);
        }

        private FieldSpec endpoint(EndpointDefinition def, ClassName parentClassName) {
            Optional<ArgumentDefinition> bodyParam = def.getArgs().stream()
                    .filter(arg -> arg.getParamType().accept(ParameterTypeVisitor.IS_BODY))
                    .findAny();
            TypeName bodyType = bodyParam
                    .map(arg -> parameterTypes.getClassName(arg.getType()))
                    .orElse(requireBody(def.getHttpMethod()) ? EMPTY_BODY : TypeName.VOID)
                    .box();
            TypeName returnType = def.getReturns().map(returnTypes::getClassName).orElse(TypeName.VOID).box();
            ParameterizedTypeName endpointType =
                    ParameterizedTypeName.get(ClassName.get(Endpoint.class), bodyType, returnType);

            TypeSpec endpointClass = TypeSpec.anonymousClassBuilder("")
                    .addSuperinterface(endpointType)
                    .addField(FieldSpec.builder(
                            TypeName.get(PathTemplate.class), "pathTemplate", Modifier.PRIVATE, Modifier.FINAL)
                            .initializer(CodeBlock.builder().add(pathTemplateInitializer(def.getHttpPath())).build())
                            .build())
                    // TODO(rfink): These fields cannot be static. Does this matter? Should we make these real
                    // instead of anonymous classes?
                    // TODO(rfink): Decide whether we want to initialize these lazily.
                    .addField(FieldSpec.builder(
                            ParameterizedTypeName.get(ClassName.get(Serializer.class), bodyType),
                            "serializer", Modifier.PRIVATE, Modifier.FINAL)
                            .initializer(serializerForType(def, parentClassName, bodyType))
                            .build())
                    .addField(FieldSpec.builder(
                            ParameterizedTypeName.get(ClassName.get(Deserializer.class), returnType),
                            "deserializer", Modifier.PRIVATE, Modifier.FINAL)
                            .initializer(deserializeForType(def.getReturns(), def.getEndpointName(), parentClassName))
                            .build())
                    .addMethod(MethodSpec.methodBuilder("renderPath")
                            .addAnnotation(Override.class)
                            .addModifiers(Modifier.PUBLIC)
                            .returns(String.class)
                            .addParameter(ParameterizedTypeName.get(Map.class, String.class, String.class), "params")
                            .addCode("return pathTemplate.fill(params);")
                            .build())
                    .addMethod(MethodSpec.methodBuilder("httpMethod")
                            .addAnnotation(Override.class)
                            .addModifiers(Modifier.PUBLIC)
                            .returns(HttpMethod.class)
                            .addCode(CodeBlock.builder().add("return $T.$L;", HttpMethod.class,
                                    def.getHttpMethod().get()).build())
                            .build())
                    .addMethod(MethodSpec.methodBuilder("requestSerializer")
                            .addAnnotation(Override.class)
                            .addModifiers(Modifier.PUBLIC)
                            .returns(ParameterizedTypeName.get(ClassName.get(Serializer.class), bodyType))
                            .addCode("return serializer;")
                            .build())
                    .addMethod(MethodSpec.methodBuilder("responseDeserializer")
                            .addAnnotation(Override.class)
                            .addModifiers(Modifier.PUBLIC)
                            .returns(ParameterizedTypeName.get(ClassName.get(Deserializer.class), returnType))
                            .addCode("return deserializer;")
                            .build())
                    .addMethod(MethodSpec.methodBuilder("errorDecoder")
                            .addAnnotation(Override.class)
                            .addModifiers(Modifier.PUBLIC)
                            .returns(OkHttpErrorDecoder.class)
                            .addCode(CodeBlock.builder()
                                    .add("return $T.INSTANCE;", DialogueOkHttpErrorDecoder.class).build())
                            .build())
                    .build();

            return FieldSpec.builder(
                    endpointType,
                    def.getEndpointName().get(),
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(CodeBlock.builder().add("$L", endpointClass).build())
                    .build();
        }

        private CodeBlock serializerForType(
                EndpointDefinition def, ClassName parentClassName, TypeName bodyType) {
            if (bodyType.equals(EMPTY_BODY)) {
                return CodeBlock.builder().add("$T.serializer();", EMPTY_BODY_CLASS).build();
            } else if (bodyType.equals(TypeName.get(Void.class))) {
                return CodeBlock.builder().add("$T.failing();", Serializers.class).build();
            } else {
                return CodeBlock.builder()
                        .add("$T.jackson($S, $L.$L);",
                                Serializers.class, def.getEndpointName(), parentClassName, MAPPER_FIELD_NAME)
                        .build();
            }
        }

        private CodeBlock deserializeForType(
                Optional<Type> returnType, EndpointName getName, ClassName parentClassName) {
            if (!returnType.isPresent()) {
                // No return value: use "empty" deserializer.
                return CodeBlock.builder()
                        .add("$T.empty($S);", Deserializers.class, getName)
                        .build();
            }

            // For "binary" conjure return type, use passthrough deserializer, otherwise use Jackson deserializer
            Type type = returnType.get();
            if (type.accept(TypeVisitor.IS_PRIMITIVE)
                    && type.accept(TypeVisitor.PRIMITIVE).equals(PrimitiveType.BINARY)) {
                return CodeBlock.builder()
                        .add("$T.passthrough();", Deserializers.class)
                        .build();
            }

            return CodeBlock.builder()
                    .add("$T.jackson($S, $L.$L, $L);",
                            Deserializers.class,
                            getName,
                            parentClassName,
                            MAPPER_FIELD_NAME,
                            TypeSpec.anonymousClassBuilder("")
                                    .addSuperinterface(ParameterizedTypeName.get(
                                            ClassName.get(TypeReference.class),
                                            returnTypes.getClassName(type).box()))
                                    .build())
                    .build();
        }

        // TODO(rfink): Integrate/consolidate with checking code in PathDefinition class
        private CodeBlock pathTemplateInitializer(HttpPath path) {
            // TODO(rfink): Re-implement subset of UriTemplateParser we need and remove Jersey dependency?
            UriTemplateParser uriTemplateParser = new UriTemplateParser(path.get());
            Splitter splitter = Splitter.on('/');
            Iterable<String> rawSegments = splitter.split(uriTemplateParser.getNormalizedTemplate());
            List<CodeBlock> segments = new ArrayList<>();
            for (String segment : rawSegments) {
                if (segment.isEmpty()) {
                    continue; // avoid empty segments; typically the first segment is empty
                }

                final String pattern;
                final String segmentName;
                if (segment.startsWith("{") && segment.endsWith("}")) {
                    pattern = "$T.variable($S)";
                    segmentName = segment.substring(1, segment.length() - 1);
                } else {
                    pattern = "$T.fixed($S)";
                    segmentName = segment;
                }
                segments.add(CodeBlock.builder()
                        .add(pattern, PathTemplate.Segment.class, segmentName).build());
            }

            return CodeBlock.builder().add("$T.of($T.of($L))",
                    com.palantir.dialogue.PathTemplate.class,
                    ImmutableList.class,
                    CodeBlock.join(segments, ",")).build();
        }

        private MethodSpec blockingClient(ServiceDefinition def) {
            TypeSpec.Builder impl = TypeSpec.anonymousClassBuilder("")
                    .addSuperinterface(serviceClassName("Blocking", def));
            def.getEndpoints().forEach(endpoint -> impl.addMethod(
                    blockingClientImpl(serviceClassName("Dialogue", def).toString(), endpoint)));

            return MethodSpec.methodBuilder("blocking")
                    .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                    .addJavadoc("Creates a synchronous/blocking client for a $L service.",
                            def.getServiceName().getName())
                    .returns(serviceClassName("Blocking", def))
                    .addParameter(Channel.class, "channel")
                    .addCode(CodeBlock.builder().add("return $L;", impl.build()).build())
                    .build();
        }

        private MethodSpec blockingClientImpl(String getServiceName, EndpointDefinition def) {
            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(def.getEndpointName().get())
                    .addModifiers(Modifier.PUBLIC)
                    .addParameters(methodParams(def));
            def.getReturns().ifPresent(type -> methodBuilder.returns(returnTypes.getClassName(type)));

            // Validate inputs
            CodeBlock.Builder validateParams = CodeBlock.builder();
            def.getArgs().stream()
                    .filter(param -> !parameterTypes.getClassName(param.getType()).isPrimitive())
                    .forEach(param ->
                            validateParams.add("$T.checkNotNull($L, \"parameter $L must not be null\");",
                                    Preconditions.class, param.getArgName(), param.getArgName()));

            // Construct request
            CodeBlock.Builder request = CodeBlock.builder();
            List<ArgumentDefinition> bodyParams = def.getArgs().stream()
                    .filter(arg -> arg.getParamType().accept(ParameterTypeVisitor.IS_BODY))
                    .collect(Collectors.toList());
            Optional<ArgumentDefinition> bodyParam = Optional.ofNullable(Iterables.getOnlyElement(bodyParams, null));
            TypeName bodyType = bodyParam.map(p -> parameterTypes.getClassName(p.getType()))
                    .orElse(requireBody(def.getHttpMethod()) ? EMPTY_BODY : TypeName.get(Void.class));
            // Add path/query/header/body parameters
            request.add("$T.Builder<$T> $L = $T.<$T>builder()",
                    Request.class, bodyType, REQUEST_VAR_NAME_BUILDER, Request.class, bodyType);
            def.getArgs().forEach(param -> addParameter(request, param));
            // Add header parameter for HEADER authentication
            def.getAuth().ifPresent(auth -> {
                verifyAuthTypeIsHeader(auth);
                request.add(".putHeaderParams($S, $T.toString($L))",
                        AUTH_HEADER_NAME, Objects.class, AUTH_HEADER_PARAM_NAME);
            });

            if (bodyType.equals(EMPTY_BODY)) {
                request.add(".body(EmptyBody.INSTANCE)");
            }

            request.add("; $T<$T> $L = $L.build();",
                    Request.class, bodyType, REQUEST_VAR_NAME, REQUEST_VAR_NAME_BUILDER);

            // Perform call and return result
            CodeBlock.Builder call = CodeBlock.builder();
            TypeName returnType = def.getReturns().map(returnTypes::getClassName)
                    .orElse(TypeName.get(Void.class)).box();
            call.add("$T<$T> _call = channel.createCall($L.$L, $L);",
                    Call.class, returnType, getServiceName, def.getEndpointName().get(), REQUEST_VAR_NAME);
            call.add("$T<$T> _response = $T.toFuture(_call);", ListenableFuture.class, returnType, Calls.class);
            call.beginControlFlow("try");
            if (def.getReturns().isPresent()) {
                call.add("return _response.get();");
            } else {
                call.add("_response.get();");
            }
            call.endControlFlow();
            call.beginControlFlow("catch($T _throwable)", Throwable.class);
            call.add("throw $T.unwrapExecutionException(_throwable);", Exceptions.class);
            call.endControlFlow();

            return methodBuilder
                    .addCode(validateParams.build())
                    .addCode(request.build())
                    .addCode(call.build())
                    .build();
        }

        private void addParameter(CodeBlock.Builder request, ArgumentDefinition param) {
            // TODO(rfink): Use native/primitive toString where possible instead of Objects#toString
            param.getParamType().accept(new ParameterType.Visitor<CodeBlock.Builder>() {
                @Override
                public CodeBlock.Builder visitBody(BodyParameterType value) {
                    return request.add(".body($L)", param.getArgName());
                }

                @Override
                public CodeBlock.Builder visitHeader(HeaderParameterType value) {
                    return param.getType().accept(new TypeVisitor.Default<CodeBlock.Builder>() {
                        @Override
                        public CodeBlock.Builder visitOptional(OptionalType optionalType) {
                            request.add("; $L.ifPresent(v -> $L.putHeaderParams($S, $T.toString(v)));",
                                    param.getArgName(), REQUEST_VAR_NAME_BUILDER, value.getParamId(),
                                    Objects.class);
                            return request.add("$L", REQUEST_VAR_NAME_BUILDER);
                        }

                        @Override
                        public CodeBlock.Builder visitDefault() {
                            return request.add(".putHeaderParams($S, $T.toString($L))",
                                    value.getParamId(), Objects.class, param.getArgName());
                        }
                    });
                }

                @Override
                public CodeBlock.Builder visitPath(PathParameterType value) {
                    return request.add(".putPathParams($S, $T.toString($L))",
                            param.getArgName(), Objects.class, param.getArgName());
                }

                @Override
                public CodeBlock.Builder visitQuery(QueryParameterType value) {
                    return param.getType().accept(new TypeVisitor.Default<CodeBlock.Builder>() {
                        @Override
                        public CodeBlock.Builder visitOptional(OptionalType optionalType) {
                            request.add("; $L.ifPresent(v -> $L.putQueryParams($S, $T.toString(v)));",
                                    param.getArgName(), REQUEST_VAR_NAME_BUILDER, value.getParamId(),
                                    Objects.class);
                            return request.add("$L", REQUEST_VAR_NAME_BUILDER);
                        }

                        @Override
                        public CodeBlock.Builder visitDefault() {
                            return request.add(".putQueryParams($S, $T.toString($L))",
                                    value.getParamId(), Objects.class, param.getArgName());
                        }
                    });
                }

                @Override
                public CodeBlock.Builder visitUnknown(String unknownType) {
                    throw new UnsupportedOperationException("Unknown parameter type: " + unknownType);
                }
            });
        }

        private List<ParameterSpec> methodParams(EndpointDefinition endpointDef) {
            List<ParameterSpec> parameterSpecs = new ArrayList<>();
            endpointDef.getAuth().ifPresent(auth -> parameterSpecs.add(authParam(auth)));
            List<ArgumentDefinition> sortedArgList = Lists.newArrayList(endpointDef.getArgs());
            sortedArgList.sort(Comparator.comparing(o ->
                    o.getParamType().accept(PARAM_SORT_ORDER) + o.getType().accept(TYPE_SORT_ORDER)));
            sortedArgList.forEach(def -> parameterSpecs.add(param(def)));
            return ImmutableList.copyOf(parameterSpecs);
        }

        private static void verifyAuthTypeIsHeader(AuthType authType) {
            if (!authType.accept(AuthTypeVisitor.IS_HEADER)) {
                throw new UnsupportedOperationException("AuthType not supported by conjure-dialogue: " + authType);
            }
        }

        private ParameterSpec param(ArgumentDefinition def) {
            ParameterSpec.Builder param =
                    ParameterSpec.builder(parameterTypes.getClassName(def.getType()), def.getArgName().get());

            param.addAnnotations(markers(def.getMarkers()));
            return param.build();
        }

        private static ParameterSpec authParam(AuthType auth) {
            verifyAuthTypeIsHeader(auth);
            return ParameterSpec.builder(AuthHeader.class, AUTH_HEADER_PARAM_NAME).build();
        }

        private Set<AnnotationSpec> markers(List<Type> markers) {
            checkArgument(markers.stream().allMatch(type -> type.accept(TypeVisitor.IS_REFERENCE)),
                    "Markers must refer to reference types.");
            return markers.stream()
                    .map(parameterTypes::getClassName)
                    .map(ClassName.class::cast)
                    .map(AnnotationSpec::builder)
                    .map(AnnotationSpec.Builder::build)
                    .collect(Collectors.toSet());
        }

        private static ClassName serviceClassName(@Nullable String prefix, ServiceDefinition def) {
            String simpleName = Strings.nullToEmpty(prefix) + def.getServiceName().getName();
            return ClassName.get(def.getServiceName().getPackage(), simpleName);
        }

        /** Produces an ordering for ParamaterType of Header, Path, Query, Body. */
        private static final ParameterType.Visitor<Integer> PARAM_SORT_ORDER = new ParameterType.Visitor<Integer>() {
            @Override
            public Integer visitBody(BodyParameterType value) {
                return 30;
            }

            @Override
            public Integer visitHeader(HeaderParameterType value) {
                return 0;
            }

            @Override
            public Integer visitPath(PathParameterType value) {
                return 10;
            }

            @Override
            public Integer visitQuery(QueryParameterType value) {
                return 20;
            }

            @Override
            public Integer visitUnknown(String unknownType) {
                return -1;
            }
        };

        /**
         * Produces a type sort ordering for use with {@link #PARAM_SORT_ORDER} such that types with known defaults come
         * after types without known defaults.
         */
        private static final Type.Visitor<Integer> TYPE_SORT_ORDER = new TypeVisitor.Default<Integer>() {
            @Override
            public Integer visitOptional(OptionalType value) {
                return 1;
            }

            @Override
            public Integer visitList(ListType value) {
                return 1;
            }

            @Override
            public Integer visitSet(SetType value) {
                return 1;
            }

            @Override
            public Integer visitMap(MapType value) {
                return 1;
            }

            @Override
            public Integer visitDefault() {
                return 0;
            }
        };
    }
}
