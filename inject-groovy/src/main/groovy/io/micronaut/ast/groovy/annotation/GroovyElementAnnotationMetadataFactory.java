package io.micronaut.ast.groovy.annotation;

import io.micronaut.ast.groovy.visitor.GroovyVarPropertyElement;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.ast.AbstractElementAnnotationMetadataFactory;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementAnnotationMetadata;
import io.micronaut.inject.ast.ElementAnnotationMetadataFactory;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;

public class GroovyElementAnnotationMetadataFactory extends AbstractElementAnnotationMetadataFactory<AnnotatedNode, AnnotationNode> {

    public GroovyElementAnnotationMetadataFactory(boolean isReadOnly, GroovyAnnotationMetadataBuilder metadataBuilder) {
        super(isReadOnly, metadataBuilder);
    }

    @Override
    public ElementAnnotationMetadataFactory readOnly() {
        return new GroovyElementAnnotationMetadataFactory(true, (GroovyAnnotationMetadataBuilder) metadataBuilder);
    }

    @Override
    protected ElementAnnotationMetadata buildForUnknown(AnnotationMetadata annotationMetadata, Element element) {
        if (element instanceof GroovyVarPropertyElement) {
            GroovyVarPropertyElement groovyVarPropertyElement = (GroovyVarPropertyElement) element;
            return new AbstractElementAnnotationMetadata(annotationMetadata) {

                @Override
                protected AnnotationMetadata createOnMissing(AnnotatedNode nativeOwnerType, AnnotatedNode nativeType) {
                    return metadataBuilder.build(nativeOwnerType, nativeType);
                }

                @Override
                protected AnnotatedNode getNativeOwnerType() {
                    return groovyVarPropertyElement.getDeclaringType().getNativeType();
                }

                @Override
                protected AnnotatedNode getNativeType() {
                    return groovyVarPropertyElement.getNativeType();
                }

            };
        }
        return super.buildForUnknown(annotationMetadata, element);
    }
}
