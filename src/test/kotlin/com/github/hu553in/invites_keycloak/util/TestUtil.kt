package com.github.hu553in.invites_keycloak.util

import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.Stream

inline fun <reified T : Any> objectProvider(sender: T?): ObjectProvider<T> {
    return object : ObjectProvider<T> {
        override fun getObject(vararg args: Any?): T {
            return sender ?: throw NoSuchBeanDefinitionException(T::class.java)
        }

        override fun getIfAvailable(): T? = sender

        override fun getIfUnique(): T? = sender

        override fun iterator(): MutableIterator<T> = listOfNotNull(sender).toMutableList().iterator()

        override fun stream(): Stream<T> = Stream.ofNullable(sender)

        override fun orderedStream(): Stream<T> = stream()
    }
}
