package io.micronaut.kotlin.processing

import com.google.devtools.ksp.closestClassDeclaration
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.micronaut.core.annotation.AnnotationClassValue
import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.core.value.OptionalValues
import io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder
import io.micronaut.inject.annotation.MutableAnnotationMetadata
import io.micronaut.inject.visitor.VisitorContext
import java.lang.annotation.Inherited
import java.lang.annotation.RetentionPolicy
import java.util.*

class KotlinAnnotationMetadataBuilder(private val annotationUtils: AnnotationUtils,
                                      private val resolver: Resolver): AbstractAnnotationMetadataBuilder<KSAnnotated, KSAnnotation>() {

    companion object {
        private fun getTypeForAnnotation(annotationMirror: KSAnnotation): KSClassDeclaration {
            return annotationMirror.annotationType.resolve().declaration as KSClassDeclaration
        }
        fun getAnnotationTypeName(annotationMirror: KSAnnotation): String {
            val type = getTypeForAnnotation(annotationMirror)
            return if (type.qualifiedName != null) {
                type.qualifiedName!!.asString()
            } else {
                println("Failed to get the qualified name of ${annotationMirror.shortName.asString()} annotation")
                annotationMirror.shortName.asString()
            }
        }
    }

    override fun isMethodOrClassElement(element: KSAnnotated): Boolean {
        return element is KSClassDeclaration || element is KSFunctionDeclaration
    }

    override fun getDeclaringType(element: KSAnnotated): String {
        val declaration = when (element) {
            is KSDeclaration -> { element }
            is KSValueParameter -> {
                when (val parent = element.parent) {
                    is KSPropertyAccessor -> { parent.receiver }
                    is KSFunctionDeclaration -> { parent }
                    else -> { null }
                }
            }
            is KSPropertyAccessor -> { element.receiver }
            else -> { null }
        }

        if (declaration != null) {
            val closestClassDeclaration = declaration.closestClassDeclaration()
            if (closestClassDeclaration != null) {
                return closestClassDeclaration.qualifiedName!!.asString()
            }
        }
        TODO("Not yet implemented")
    }

    override fun getTypeForAnnotation(annotationMirror: KSAnnotation): KSClassDeclaration {
        return KotlinAnnotationMetadataBuilder.getTypeForAnnotation(annotationMirror)
    }

    override fun hasAnnotation(element: KSAnnotated, annotation: Class<out Annotation>): Boolean {
        return hasAnnotation(element, annotation.name)
    }

    override fun hasAnnotation(element: KSAnnotated, annotation: String): Boolean {
        return element.annotations.map {
            it.annotationType.resolve().declaration.qualifiedName
        }.any {
            it?.asString() == annotation
        }
    }

    override fun hasAnnotations(element: KSAnnotated): Boolean {
        return element.annotations.iterator().hasNext()
    }

    override fun getAnnotationTypeName(annotationMirror: KSAnnotation): String {
        return KotlinAnnotationMetadataBuilder.getAnnotationTypeName(annotationMirror)
    }

    override fun getElementName(element: KSAnnotated): String {
        if (element is KSDeclaration) {
            return if (element is KSClassDeclaration) {
                element.qualifiedName!!.asString()
            } else {
                element.simpleName.asString()
            }
        }
        TODO("Not yet implemented")
    }

    override fun getAnnotationsForType(element: KSAnnotated): MutableList<out KSAnnotation> {
        return element.annotations.toMutableList()
    }

    override fun postProcess(annotationMetadata: MutableAnnotationMetadata, element: KSAnnotated) {
        if (element is KSValueParameter && element.type.resolve().isMarkedNullable) {
            annotationMetadata.addDeclaredAnnotation(AnnotationUtil.NULLABLE, emptyMap())
        }
    }

    override fun buildHierarchy(
        element: KSAnnotated,
        inheritTypeAnnotations: Boolean,
        declaredOnly: Boolean
    ): MutableList<KSAnnotated> {
        if (declaredOnly) {
            return mutableListOf(element)
        }
        if (element is KSClassDeclaration) {
            val hierarchy = mutableListOf<KSAnnotated>()
            hierarchy.add(element)
            if (element.classKind == ClassKind.ANNOTATION_CLASS) {
                return hierarchy
            }
            populateTypeHierarchy(element, hierarchy)
            hierarchy.reverse()
            return hierarchy
        } else if (element is KSFunctionDeclaration) {
            val hierarchy = mutableListOf<KSAnnotated>()
            hierarchy.add(element)
            var overidden = element.findOverridee()
            while (overidden != null) {
                hierarchy.add(overidden)
                overidden = (overidden as KSFunctionDeclaration).findOverridee()
            }
            return hierarchy
        } else {
            return mutableListOf(element)
        }
    }

    override fun readAnnotationRawValues(
        originatingElement: KSAnnotated,
        annotationName: String,
        member: KSAnnotated,
        memberName: String,
        annotationValue: Any,
        annotationValues: MutableMap<CharSequence, Any>
    ) {
        if (!annotationValues.containsKey(memberName)) {
            val value = readAnnotationValue(originatingElement, member, memberName, annotationValue)
            if (value != null) {
                validateAnnotationValue(originatingElement, annotationName, member, memberName, value)
                annotationValues[memberName] = value
            }
        }
    }

    override fun isValidationRequired(member: KSAnnotated?): Boolean {
        TODO("Not yet implemented")
    }

    override fun addError(originatingElement: KSAnnotated, error: String) {
        TODO("Not yet implemented")
    }

    override fun addWarning(originatingElement: KSAnnotated, warning: String) {
        TODO("Not yet implemented")
    }

    override fun readAnnotationValue(
        originatingElement: KSAnnotated,
        member: KSAnnotated,
        memberName: String,
        annotationValue: Any
    ): Any? {
        if (annotationValue is Collection<*>) {
            return annotationValue.map {
                readAnnotationValue(originatingElement, it)
            }
        }
        return readAnnotationValue(originatingElement, annotationValue)
    }

    override fun readAnnotationDefaultValues(annotationMirror: KSAnnotation): MutableMap<out KSDeclaration, *> {
        return mutableMapOf<KSDeclaration, Any>()
    }

    override fun readAnnotationDefaultValues(
        annotationName: String,
        annotationType: KSAnnotated
    ): MutableMap<out KSDeclaration, *> {
        return mutableMapOf<KSDeclaration, Any>()
    }

    override fun readAnnotationRawValues(annotationMirror: KSAnnotation): MutableMap<out KSDeclaration, *> {
        val map = mutableMapOf<KSDeclaration, Any>()
        val declaration = annotationMirror.annotationType.resolve().declaration as KSClassDeclaration
        declaration.getAllProperties().forEach { prop ->
            val argument = annotationMirror.arguments.find { it.name == prop.simpleName }
            if (argument?.value != null) {
                map[prop] = argument.value!!
            }
        }
        return map
    }

    override fun getAnnotationValues(
        originatingElement: KSAnnotated,
        member: KSAnnotated,
        annotationType: Class<*>
    ): OptionalValues<*> {
        val annotationMirrors: MutableList<KSAnnotation> = (member as KSPropertyDeclaration).getter!!.annotations.toMutableList()
        annotationMirrors.addAll(member.annotations.toList())
        val annotationName = annotationType.name
        for (annotationMirror in annotationMirrors) {
            if (annotationMirror.annotationType.resolve().declaration.qualifiedName?.asString() == annotationName) {
                val values: Map<out KSDeclaration, *> = readAnnotationRawValues(annotationMirror)
                val converted: MutableMap<CharSequence, Any> = mutableMapOf()
                for ((key, value1) in values) {
                    val value = value1!!
                    readAnnotationRawValues(
                        originatingElement,
                        annotationName,
                        member,
                        key.simpleName.asString(),
                        value,
                        converted
                    )
                }
                return OptionalValues.of(Any::class.java, converted)
            }
        }
        return OptionalValues.empty<Any>()
    }

    override fun getAnnotationMemberName(member: KSAnnotated): String {
        if (member is KSDeclaration) {
            return member.simpleName.asString()
        }
        TODO("Not yet implemented")
    }

    override fun getRepeatableName(annotationMirror: KSAnnotation): String? {
        return getRepeatableNameForType(annotationMirror.annotationType)
    }

    override fun getRepeatableNameForType(annotationType: KSAnnotated): String? {
        val name = java.lang.annotation.Repeatable::class.java.name
        val repeatable = annotationType.annotations.find {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == name
        }
        if (repeatable != null) {
            val value = repeatable.arguments.find { it.name?.asString() == "value" }?.value
            if (value != null) {
                val declaration = (value as KSType).declaration as KSClassDeclaration
                return declaration.toClassName()
            }
        }
        return null
    }

    override fun getAnnotationMirror(annotationName: String): Optional<KSAnnotated> {
        return Optional.ofNullable(resolver.getClassDeclarationByName(annotationName))
    }

    override fun getAnnotationMember(originatingElement: KSAnnotated, member: CharSequence): KSAnnotated? {
        if (originatingElement is KSAnnotation) {
            return originatingElement.arguments.find { it.name == member }
        }
        return null
    }

    override fun createVisitorContext(): VisitorContext {
        return annotationUtils.newVisitorContext()
    }

    override fun getRetentionPolicy(annotation: KSAnnotated): RetentionPolicy {
        var retention = annotation.annotations.find {
            getAnnotationTypeName(it) == java.lang.annotation.Retention::class.java.name
        }
        if (retention != null) {
            val value = retention.arguments.find { it.name?.asString() == "value" }?.value
            if (value != null) {
                return value as RetentionPolicy
            }
        } else {
            retention = annotation.annotations.find {
                getAnnotationTypeName(it) == Retention::class.java.name
            }
            if (retention != null) {
                val value = retention.arguments.find { it.name?.asString() == "value" }?.value
                if (value != null) {
                    val entry = AnnotationRetention.valueOf((value as KSType).declaration.qualifiedName!!.getShortName())
                    return when (entry) {
                        AnnotationRetention.RUNTIME -> {
                            RetentionPolicy.RUNTIME
                        }
                        AnnotationRetention.SOURCE -> {
                            RetentionPolicy.SOURCE
                        }
                        AnnotationRetention.BINARY -> {
                            RetentionPolicy.CLASS
                        }
                    }
                }
            }
        }
        return RetentionPolicy.RUNTIME
    }

    override fun isInheritedAnnotation(annotationMirror: KSAnnotation): Boolean {
        return annotationMirror.annotationType.resolve().declaration.annotations.any {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == Inherited::class.qualifiedName
        }
    }

    override fun isInheritedAnnotationType(annotationType: KSAnnotated): Boolean {
        return annotationType.annotations.any {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == Inherited::class.qualifiedName
        }
    }

    private fun populateTypeHierarchy(element: KSClassDeclaration, hierarchy: MutableList<KSAnnotated>) {
        element.superTypes.forEach {
            val declaration = it.resolve().declaration
            hierarchy.add(declaration)
            populateTypeHierarchy(declaration as KSClassDeclaration, hierarchy)
        }
    }

    private fun readAnnotationValue(originatingElement: KSAnnotated, value: Any?): Any? {
        if (value == null) {
            return null
        }
        if (value is KSType) {
            val declaration = value.declaration
            if (declaration is KSClassDeclaration) {
                if (declaration.classKind == ClassKind.ENUM_ENTRY) {
                    return declaration.qualifiedName?.getShortName()
                }
                if (declaration.classKind == ClassKind.CLASS ||
                    declaration.classKind == ClassKind.INTERFACE ||
                    declaration.classKind == ClassKind.ANNOTATION_CLASS) {
                    return AnnotationClassValue<Any>(declaration.toClassName())
                }
            }
        }
        if (value is KSAnnotation) {
            return readNestedAnnotationValue(originatingElement, value)
        }
        return value
    }

    override fun getAnnotationMembers(annotationType: String): MutableMap<String, out KSAnnotated> {
        val annotationMirror = getAnnotationMirror(annotationType)
        val members = mutableMapOf<String, KSAnnotated>()
        if (annotationMirror.isPresent) {
            (annotationMirror.get() as KSClassDeclaration).getDeclaredProperties()
                .forEach {
                    members[it.simpleName.asString()] = it
                }
        }
        return members
    }

    override fun hasSimpleAnnotation(element: KSAnnotated, simpleName: String): Boolean {
        return element.annotations.any {
            it.annotationType.resolve().declaration.simpleName.asString() == simpleName
        }
    }
}
