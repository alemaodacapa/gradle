/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.attributes

import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.HasAttributes
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.internal.artifacts.JavaEcosystemSupport
import org.gradle.api.internal.provider.DefaultProperty
import org.gradle.api.internal.provider.DefaultProvider
import org.gradle.api.internal.provider.PropertyHost
import org.gradle.api.internal.provider.Providers
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.util.AttributeTestUtil
import org.gradle.util.TestUtil

/**
 * Unit tests for the {@link DefaultMutableAttributeContainer} class.
 */
final class DefaultMutableAttributeContainerTest extends AbstractAttributeContainerTest {
    @Override
    protected <T> DefaultMutableAttributeContainer getContainer(Map<Attribute<T>, T> attributes = [:]) {
        DefaultMutableAttributeContainer container = new DefaultMutableAttributeContainer(AttributeTestUtil.attributesFactory(), AttributeTestUtil.attributeValueIsolator())
        attributes.forEach {key, value ->
            container.attribute(key, value)
        }
        return container
    }

    def "lazy attributes are evaluated in insertion order"() {
        def container = getContainer()
        def actual = []
        def expected = []
        (1..100).each { idx ->
            def testAttribute = Attribute.of("test"+idx, String)
            expected << idx
            container.attributeProvider(testAttribute, Providers.<String>changing {
                actual << idx
                "value " + idx
            })
        }
        expect:
        container.asImmutable().keySet().size() == 100
        actual == expected
    }

    def "realizing the value of lazy attributes may cause other attributes to be realized"() {
        def container = getContainer()
        def firstAttribute = Attribute.of("first", String)
        def secondAttribute = Attribute.of("second", String)
        container.attributeProvider(firstAttribute, Providers.<String>changing {
            // side effect is to evaluate the secondAttribute's value and prevent
            // it from changing by removing it from the list of "lazy attributes"
            container.getAttribute(secondAttribute)
            "first"
        })
        container.attributeProvider(secondAttribute, Providers.of("second"))

        expect:
        container.asImmutable().keySet() == [secondAttribute, firstAttribute] as Set

        and:
        def events = outputEventListener.events.findAll { it.logLevel == LogLevel.WARN }
        events.size() == 1
        events[0].message.startsWith("Querying the contents of an attribute container while realizing attributes of the container. This behavior has been deprecated. This will fail with an error in Gradle 9.0")
    }

    def "realizing the value of lazy attributes cannot add new attributes to the container"() {
        def container = getContainer()
        def firstAttribute = Attribute.of("first", String)
        def secondAttribute = Attribute.of("second", String)
        container.attributeProvider(firstAttribute, Providers.<String>changing {
            container.attribute(secondAttribute, "second" )
            "first"
        })

        when:
        container.asImmutable()
        then:
        def e = thrown(IllegalStateException)
        e.message == "Cannot add new attribute 'second' while realizing all attributes of the container."
    }

    def "realizing the value of lazy attributes cannot add new lazy attributes to the container"() {
        def container = getContainer()
        def firstAttribute = Attribute.of("first", String)
        def secondAttribute = Attribute.of("second", String)
        container.attributeProvider(firstAttribute, Providers.<String>changing {
            container.attributeProvider(secondAttribute, Providers.of("second"))
            "first"
        })

        when:
        container.asImmutable()
        then:
        def e = thrown(IllegalStateException)
        e.message == "Cannot add new attribute 'second' while realizing all attributes of the container."
    }

    def "adding mismatched attribute types fails fast"() {
        Property<Integer> testProperty = new DefaultProperty<>(Mock(PropertyHost), Integer).convention(1)
        def testAttribute = Attribute.of("test", String)
        def container = getContainer()

        when:
        //noinspection GroovyAssignabilityCheck - meant to fail
        container.attributeProvider(testAttribute, testProperty)
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unexpected type for attribute 'test' provided. Expected a value of type java.lang.String but found a value of type java.lang.Integer.")
    }

    def "adding mismatched attribute types fails when retrieving the key when the provider does not know the type"() {
        Provider<?> testProperty = new DefaultProvider<?>( { 1 })
        def testAttribute = Attribute.of("test", String)
        def container = getContainer()

        when:
        //noinspection GroovyAssignabilityCheck - meant to fail
        container.attributeProvider(testAttribute, testProperty)
        container.getAttribute(testAttribute)
        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Unexpected type for attribute 'test' provided. Expected a value of type java.lang.String but found a value of type java.lang.Integer.")
    }

    def "equals should return true for 2 containers with different provider instances that return the same value"() {
        Property<String> testProperty1 = new DefaultProperty<>(Mock(PropertyHost), String).convention("value")
        Property<String> testProperty2 = new DefaultProperty<>(Mock(PropertyHost), String).convention("value")
        def testAttribute = Attribute.of("test", String)
        def container1 = getContainer()
        def container2 = getContainer()

        when:
        container1.attributeProvider(testAttribute, testProperty1)
        container2.attributeProvider(testAttribute, testProperty2)

        then:
        container1 == container2
        container2 == container1
    }

    def "equals should return false for 2 containers with different provider instances that return different values"() {
        Property<String> testProperty1 = new DefaultProperty<>(Mock(PropertyHost), String).convention("value1")
        Property<String> testProperty2 = new DefaultProperty<>(Mock(PropertyHost), String).convention("value2")
        def testAttribute = Attribute.of("test", String)
        def container1 = getContainer()
        def container2 = getContainer()

        when:
        container1.attributeProvider(testAttribute, testProperty1)
        container2.attributeProvider(testAttribute, testProperty2)

        then:
        container1 != container2
        container2 != container1
    }

    def "hashCode should return the same result for 2 containers with different provider instances that return the same value"() {
        Property<String> testProperty1 = new DefaultProperty<>(Mock(PropertyHost), String).convention("value")
        Property<String> testProperty2 = new DefaultProperty<>(Mock(PropertyHost), String).convention("value")
        def testAttribute = Attribute.of("test", String)
        def container1 = getContainer()
        def container2 = getContainer()

        when:
        container1.attributeProvider(testAttribute, testProperty1)
        container2.attributeProvider(testAttribute, testProperty2)

        then:
        container1.hashCode() == container2.hashCode()
    }

    def "hashCode should return different result for 2 containers with different provider instances that return different values"() {
        Property<String> testProperty1 = new DefaultProperty<>(Mock(PropertyHost), String).convention("value1")
        Property<String> testProperty2 = new DefaultProperty<>(Mock(PropertyHost), String).convention("value2")
        def testAttribute = Attribute.of("test", String)
        def container1 = getContainer()
        def container2 = getContainer()

        when:
        container1.attributeProvider(testAttribute, testProperty1)
        container2.attributeProvider(testAttribute, testProperty2)

        then:
        container1.hashCode() != container2.hashCode()
    }

    def "adding attribute should override replace existing lazy attribute"() {
        given: "a container with testAttr set to a provider"
        def testAttr = Attribute.of("test", String)
        def container = getContainer()
        Property<String> testProvider = new DefaultProperty<>(Mock(PropertyHost), String).convention("lazy value")
        container.attributeProvider(testAttr, testProvider)

        when: "adding a set value testAttr"
        container.attribute(testAttr, "set value")

        then: "the set value should be retrievable"
        "set value" == container.getAttribute(testAttr)
    }

    def "adding lazy attribute should override replace existing attribute"() {
        given: "a container with testAttr set to a fixed value"
        def testAttr = Attribute.of("test", String)
        def container = getContainer()
        container.attribute(testAttr, "set value")

        when: "adding a lazy testAttr"
        Property<String> testProvider = new DefaultProperty<>(Mock(PropertyHost), String).convention("lazy value")
        container.attributeProvider(testAttr, testProvider)

        then: "the lazy provider should be retrievable"
        "lazy value" == container.getAttribute(testAttr)
    }

    @SuppressWarnings('GroovyAccessibility')
    def "toString should not change the internal state of the class"() {
        given: "a container and a lazy and non-lazy attribute"
        def container = getContainer()
        def testEager = Attribute.of("eager", String)
        def testLazy = Attribute.of("lazy", String)
        Property<String> testProvider = new DefaultProperty<>(Mock(PropertyHost), String).convention("lazy value")

        when: "the attributes are added to the container"
        container.attribute(testEager, "eager value")
        container.attributeProvider(testLazy, testProvider)

        then: "they are located in proper internal collections"
        container.@attributes.containsKey(testEager)
        !container.@attributes.containsKey(testLazy)
        container.@lazyAttributes.containsKey(testLazy)
        !container.@lazyAttributes.containsKey(testEager)

        when: "calling toString"
        def result = container.toString()

        then: "the result should not change the internals of the class"
        result == "{eager=eager value, lazy=property(java.lang.String, fixed(class java.lang.String, lazy value))}"
        container.@attributes.containsKey(testEager)
        !container.@attributes.containsKey(testLazy)
        container.@lazyAttributes.containsKey(testLazy)
        !container.@lazyAttributes.containsKey(testEager)
    }

    def "can query contents of container"() {
        def thing = Attribute.of("thing", String)
        def thing2 = Attribute.of("thing2", String)

        when:
        def container = getContainer()

        then:
        container.empty
        container.keySet().empty
        !container.contains(thing)
        container.getAttribute(thing) == null

        when:
        container.attribute(thing, "thing")

        then:
        !container.empty
        container.keySet() == [thing] as Set
        container.contains(thing)
        container.getAttribute(thing) == "thing"

        when:
        container.attributeProvider(thing2, Providers.of("value"))

        then:
        !container.empty
        container.keySet() == [thing, thing2] as Set
        container.contains(thing2)
        container.getAttribute(thing2) == "value"

    }

    def "A copy of an attribute container contains the same attributes and the same values as the original"() {
        given:
        def container = getContainer()
        container.attribute(Attribute.of("a1", Integer), 1)
        container.attribute(Attribute.of("a2", String), "2")
        container.attributeProvider(Attribute.of("a3", String), Providers.of("3"))

        when:
        def copy = container.asImmutable()

        then:
        copy.keySet().size() == 3
        copy.getAttribute(Attribute.of("a1", Integer)) == 1
        copy.getAttribute(Attribute.of("a2", String)) == "2"
        copy.getAttribute(Attribute.of("a3", String)) == "3"
    }

    def "changes to attribute container are not seen by immutable copy"() {
        given:
        AttributeContainerInternal container = getContainer()
        container.attribute(Attribute.of("a1", Integer), 1)
        container.attribute(Attribute.of("a2", String), "2")
        container.attributeProvider(Attribute.of("a3", String), Providers.of("3"))
        def immutable = container.asImmutable()

        when:
        container.attribute(Attribute.of("a1", Integer), 2)
        container.attribute(Attribute.of("a3", String), "3")
        container.attributeProvider(Attribute.of("a3", String), Providers.of("4"))

        then:
        immutable.keySet().size() == 3
        immutable.getAttribute(Attribute.of("a1", Integer)) == 1
        immutable.getAttribute(Attribute.of("a2", String)) == "2"
        immutable.getAttribute(Attribute.of("a3", String)) == "3"
    }

    def "An attribute container can provide the attributes through the HasAttributes interface"() {
        given:
        def container = getContainer()
        container.attribute(Attribute.of("a1", Integer), 1)
        container.attributeProvider(Attribute.of("a2", String), Providers.of("2"))

        when:
        HasAttributes access = container

        then:
        access.attributes.getAttribute(Attribute.of("a1", Integer)) == 1
        access.attributes.getAttribute(Attribute.of("a2", String)) == "2"
        access.attributes == access
    }

    def "has useful string representation"() {
        def a = Attribute.of("a", String)
        def b = Attribute.of("b", String)
        def c = Attribute.of("c", String)

        when:
        def container = getContainer()

        then:
        container.toString() == "{}"
        container.asImmutable().toString() == "{}"

        when:
        container.attribute(b, "b")
        container.attribute(c, "c")
        container.attributeProvider(a, Providers.of("a"))

        then:
        container.toString() == "{a=fixed(class java.lang.String, a), b=b, c=c}"
        container.asImmutable().toString() == "{a=a, b=b, c=c}"
    }

    def "can access lazy elements while iterating over keySet"() {
        def container = getContainer()

        when:
        container.attributeProvider(Attribute.of("a", String), Providers.of("foo"))
        container.attributeProvider(Attribute.of("b", String), Providers.of("foo"))

        then:
        for (Attribute<Object> attribute : container.keySet()) {
            container.getAttribute(attribute)
        }
    }

    def "can add deprecated usage then add libraryelements and convert to immutable"() {
        def container = getContainer()

        when:
        container.attribute(Usage.USAGE_ATTRIBUTE, TestUtil.objectInstantiator().named(Usage, JavaEcosystemSupport.DEPRECATED_JAVA_API_JARS))
        container.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, TestUtil.objectInstantiator().named(LibraryElements, "aar"))

        then:
        def immutable = container.asImmutable()
        immutable.keySet().size() == 2
        immutable.getAttribute(Usage.USAGE_ATTRIBUTE).name == Usage.JAVA_API
        immutable.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).name == "aar"
    }

    def "can add libraryelements then add deprecated usage and convert to immutable"() {
        def container = getContainer()

        when:
        container.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, TestUtil.objectInstantiator().named(LibraryElements, "aar"))
        container.attribute(Usage.USAGE_ATTRIBUTE, TestUtil.objectInstantiator().named(Usage, JavaEcosystemSupport.DEPRECATED_JAVA_API_JARS))

        then:
        def immutable = container.asImmutable()
        immutable.keySet().size() == 2
        immutable.getAttribute(Usage.USAGE_ATTRIBUTE).name == Usage.JAVA_API
        immutable.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE).name == "jar"
    }
}
