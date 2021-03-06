package net.corda.serialization.internal.amqp.custom

import net.corda.core.serialization.DESERIALIZATION_CACHE_PROPERTY
import net.corda.core.serialization.SerializationContext
import net.corda.serialization.internal.amqp.*
import org.apache.qpid.proton.codec.Data
import java.lang.reflect.Type
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

object X509CertificateSerializer
    : CustomSerializer.Implements<X509Certificate>(
        X509Certificate::class.java
) {
    override val schemaForDocumentation = Schema(listOf(RestrictedType(
            type.toString(),
            "",
            listOf(type.toString()),
            AMQPTypeIdentifiers.primitiveTypeName(ByteArray::class.java),
            descriptor,
            emptyList()
    )))

    override fun writeDescribedObject(obj: X509Certificate, data: Data, type: Type, output: SerializationOutput,
                                      context: SerializationContext) {
        output.writeObject(obj.encoded, data, clazz, context)
    }

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput,
                            context: SerializationContext): X509Certificate {
        val bits = input.readObject(obj, schemas, ByteArray::class.java, context) as ByteArray
        @Suppress("unchecked_cast")
        return (context.properties[DESERIALIZATION_CACHE_PROPERTY] as? MutableMap<CacheKey, X509Certificate>)
            ?.computeIfAbsent(CacheKey(bits)) { key ->
                generateCertificate(key.bytes)
            } ?: generateCertificate(bits)
    }

    private fun generateCertificate(bits: ByteArray): X509Certificate {
        return CertificateFactory.getInstance("X.509").generateCertificate(bits.inputStream()) as X509Certificate
    }
}
