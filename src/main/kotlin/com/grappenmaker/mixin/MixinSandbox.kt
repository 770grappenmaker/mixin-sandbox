package com.grappenmaker.mixin

import com.grappenmaker.jvmutil.*
import com.grappenmaker.mappings.ClasspathLoaders
import com.grappenmaker.mappings.LambdaAwareRemapper
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.spongepowered.asm.launch.MixinBootstrap
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.MixinEnvironment.Phase
import org.spongepowered.asm.mixin.Mixins
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import org.spongepowered.asm.mixin.transformer.IMixinTransformer
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged
import org.spongepowered.asm.service.MixinService
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.net.URL
import java.security.ProtectionDomain
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.properties.ReadOnlyProperty
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.reflect.KProperty

internal interface NewFeature {
    fun somethingNew()
}

internal class TestMixedIn {
    fun test() = "Hello, world!"
}

internal fun agentmain(arg: String?, inst: Instrumentation) = premain(arg, inst)

internal fun premain(arg: String?, inst: Instrumentation) {
    val baseDir = Path("C:\\Users\\Koen\\lunar-dumps\\new-fabric")
    baseDir.createDirectories()

    inst.addTransformer(object : ClassFileTransformer {
        override fun transform(
            loader: ClassLoader?,
            className: String,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray
        ): ByteArray? {
            val parts = className.split("/")
            val dir = parts.dropLast(1).fold(baseDir) { acc, curr -> acc.resolve(curr) }
            dir.createDirectories()
            dir.resolve("${parts.last()}.class").writeBytes(classfileBuffer)

            return null
        }
    })

    inst.getAppClasses().forEach {
        try {
            inst.retransformClasses(it)
        } catch (ignored: Throwable) {}
    }
//    inst.addTransformer(
//        SandboxedMixinTransformer(
//            SandboxedMixinLoader().state.apply {
//                initialize()
//                registerSpoofedMixin<TestMixin>()
//            }
//        )
//    )
//
//    inst.addTransformer(
//        SandboxedMixinTransformer(
//            SandboxedMixinLoader().state.apply {
//                initialize()
//                registerSpoofedMixin<TestMixin2>()
//            }
//        )
//    )
}

@Mixin(TestMixedIn::class)
internal class TestMixin : NewFeature {
    @Inject(method = ["test"], at = [At("HEAD")])
    fun test(ci: CallbackInfoReturnable<String>) = println("Injected!")
    override fun somethingNew() = println("VERY COOL")
}

@Mixin(TestMixedIn::class)
internal class TestMixin2 {
    @Inject(method = ["test"], at = [At("HEAD")])
    fun test(ci: CallbackInfoReturnable<String>) = println("Electric boogaloo")
}

internal fun main() {
    val test = TestMixedIn()
    println(test.test())
    (test as NewFeature).somethingNew()
}

/**
 * Implements a [ClassFileTransformer] that passes all loaded classes through the wrapped [mixin] state
 */
public class SandboxedMixinTransformer(
    private val mixin: SandboxedMixinState
) : ClassFileTransformer {
    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray? = mixin.transformOrNull(className, classfileBuffer)
}

/**
 * A [ClassLoader] that is capable of "sandboxing" Spongepowered Mixin. The [parent] loader can be specified,
 * which will be used to load non-sandboxed classes. A [loader] can be specified, which is responsible for providing
 * class bytes by a given name (forward slashes are the package separator).
 *
 * fixupConflicts determines whether the remapper for the mixin sandbox renames possibly colliding mixed-in methods
 */
public class SandboxedMixinLoader(
    private val parent: ClassLoader = getSystemClassLoader(),
    private val loader: (name: String) -> ByteArray? = ClasspathLoaders.fromLoader(parent),
    fixupConflicts: Boolean = true,
) : ClassLoader(parent) {
    private val finders = FinderContext()
    private val injectedResources = mutableMapOf<String, ByteArray>()
    private val tempFiles = mutableMapOf<String, URL>()
    private val loaderExclusions = hashSetOf("com.grappenmaker.mixin.MixinAccess")

    private val systemClasses = setOf(
        "java.", "javax.", "org.xml.", "org.w3c.", "sun.", "jdk.",
        "com.sun.management.", "kotlin.", "kotlinx.", "org.objectweb.",
        "com.grappenmaker.mixin.SandboxedMixinState"
    )

    /**
     * Responsible for managing the state of the sandboxed mixin environment
     */
    public val state: SandboxedMixinState = SandboxedMixinState(this, fixupConflicts)

    init {
        finders.findNamedClass("org/spongepowered/asm/util/Constants") {
            methods {
                clinitTransform {
                    replaceCall {
                        method ownedBy asmTypeOf<Package>()
                        method named "getName"
                        replacement {
                            pop()
                            loadConstant("org.spongepowered.asm.mixin")
                        }
                    }
                }
            }
        }
    }

    override fun findClass(name: String): Class<*> {
        if (name == javaClass.name) return javaClass

        return findLoadedClass(name) ?: if (shouldLoadParent(name)) parent.loadClass(name) else {
            val internalName = name.replace('.', '/')
            val bytes = getResourceAsStream("$internalName.class")?.readBytes()
                ?: throw ClassNotFoundException("Could not sandbox mixin: resource unavailable: $internalName")

            val resultBytes = finders.transform(this, internalName, bytes) ?: bytes
            defineClass(name, resultBytes, 0, resultBytes.size)
        }
    }

    override fun loadClass(name: String): Class<*> = findClass(name)
    private fun shouldLoadParent(name: String) = name in loaderExclusions || systemClasses.any { name.startsWith(it) }

    /**
     * Allows you to add an "injected resource" to this loader, that is, when a resource with [name] is requested,
     * a stream (or temporary file) with [bytes] is returned (instead of delegating to the parent loader)
     */
    public fun injectResource(name: String, bytes: ByteArray) {
        injectedResources[name] = bytes
    }

    override fun getResource(name: String): URL? = tempFiles[name] ?: injectedResources[name]?.let {
        File.createTempFile("injected-resource", null).run {
            writeBytes(injectedResources.getValue(name))
            deleteOnExit()
            toURI().toURL().also { tempFiles[name] = it }
        }
    } ?: super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        if (name in injectedResources) Collections.enumeration(listOf(getResource(name)))
        else super.getResources(name)

    override fun getResourceAsStream(name: String): InputStream? =
        injectedResources[name]?.let { ByteArrayInputStream(it) } ?: super.getResourceAsStream(name)

    /**
     * Allows you to prevent a certain class with a given binary [name] from being sandboxed
     */
    public fun addLoaderExclusion(name: String) {
        loaderExclusions += name
    }

    internal fun getClassBytes(name: String) = loader(name) ?: getResourceAsStream("$name.class")?.readBytes()
}

private fun createMixinAccessor(loader: ClassLoader) = loader
    .loadClass("com.grappenmaker.mixin.MixinAccessImpl")
    .getField("INSTANCE").also { it.isAccessible = true }[null] as MixinAccess

/**
 * Keeps track of the state of the mixin environment. Allows you to interact with the sandbox.
 *
 * [initialize] must be invoked before calling any other method
 */
public class SandboxedMixinState(
    private val loader: SandboxedMixinLoader,
    private val fixupConflicts: Boolean,
) : MixinAccess by createMixinAccessor(loader) {
    // be careful to not load classes in the wrong context
    internal lateinit var transformer: Any

    /**
     * Whether this [SandboxedMixinState] has been initialized
     */
    public var initialized: Boolean = false
        private set

    /**
     * Initializes this [SandboxedMixinState], does nothing when [initialized] is true
     */
    public fun initialize() {
        if (initialized) return

        injectService(
            "org.spongepowered.asm.service.IMixinService",
            "com.grappenmaker.mixin.SandboxedMixinService"
        )

        injectService(
            "org.spongepowered.asm.service.IMixinServiceBootstrap",
            "com.grappenmaker.mixin.DummyServiceBootstrap"
        )

        injectService(
            "org.spongepowered.asm.service.IGlobalPropertyService",
            "com.grappenmaker.mixin.DummyPropertyService"
        )

        bootstrap()
        validate()
        gotoDefault()

        initialized = true
    }

    /**
     * Registers a mixin configuration, available on the classpath of the parent loader with a given [resourceName]
     */
    public fun registerMixin(resourceName: String) {
        if (loader.getResourceAsStream(resourceName)?.close() == null)
            error("Mixin config $resourceName does not exist!")

        addMixin(resourceName)
    }

    private fun isInherited(node: ClassNode, method: MethodNode): Boolean {
        val initial = listOfNotNull(node.superName) + node.interfaces
        val queue = ArrayDeque<String>()
        val seen = initial.toHashSet()
        queue += initial

        while (queue.isNotEmpty()) {
            val curr = queue.removeLast()
            val cn = loader.getClassBytes(curr)?.asClassNode() ?: continue
            if (cn.methods.any { it.desc == method.desc && it.name == method.name }) return true

            (listOfNotNull(cn.superName) + cn.interfaces)
                .forEach { if (seen.add(it)) queue += it }
        }

        return false
    }

    private fun ByteArray.fixupConflicts(original: ByteArray): ByteArray {
        if (this === original) return this

        val reader = ClassReader(this)
        val writer = LoaderClassWriter(loader, reader, 0)
        val node = ClassNode().also { reader.accept(it, 0) }

        val annotation = "L${internalNameOf<MixinMerged>()};"
        val merged = node.methods
            .filter { m -> m.visibleAnnotations?.any { it.desc == annotation } == true && !isInherited(node, m) }

        if (merged.isEmpty()) return this

        val mergedCounter by counter()
        val id = Random.nextUInt()
        val mapping = merged.associate {
            "${reader.className}.${it.name}${it.desc}" to "$\$mixin${id}Handler$mergedCounter"
        }

        node.accept(LambdaAwareRemapper(writer, SimpleRemapper(mapping)))
        return writer.toByteArray()
    }

    /**
     * Transforms a class represented by given [bytes] with a certain [internalName] using the mixin environment
     */
    public fun transform(internalName: String, bytes: ByteArray): ByteArray =
        transform(transformer, internalName.replace('/', '.'), bytes)
            .let { if (fixupConflicts) it.fixupConflicts(bytes) else it }

    /**
     * Transforms a class represented by given [bytes] with a certain [internalName] using the mixin environment.
     *
     * Returns null when mixin was not interested in visiting this class
     */
    public fun transformOrNull(internalName: String, bytes: ByteArray): ByteArray? =
        transform(internalName, bytes).takeIf { it !== bytes }

    private val dynamicMixinCounter by counter()

    /**
     * Registers a dynamically/programatically defined mixin configuration represented by [config]
     */
    public fun registerDynamicMixin(config: ByteArray) {
        val name = "dynamic-mixin$dynamicMixinCounter.json"
        loader.injectResource(name, config)
        registerMixin(name)
    }

    /**
     * Registers a dynamically/programatically defined mixin configuration represented by [config]
     */
    public fun registerDynamicMixin(config: String) {
        registerDynamicMixin(config.encodeToByteArray())
    }

    private val spoofedMixinCounter by counter()

    /**
     * Allows you to register a mixin class given by a type [T]. Note that the class will be moved into a fake package,
     * which means that reflective class references to [T] might not result in exactly the expected results.
     *
     * Contrary to default mixin limitations, the class given by [T] does not have to be located inside a package
     * containing only mixin classes. [T] also can be a nested class.
     */
    public inline fun <reified T : Any> registerSpoofedMixin() {
        registerSpoofedMixin(internalNameOf<T>())
    }

    /**
     * Allows you to register a mixin class given by an [internalName].
     * Note that the class will be moved into a fake package,
     * which means that reflective class references to the class might not result in exactly the expected results.
     *
     * Contrary to default mixin limitations, the given class does not have to be located inside a package
     * containing only mixin classes. It also can be a nested class.
     */
    public fun registerSpoofedMixin(internalName: String) {
        val prefix = "com/grappenmaker/mixin/spoofed$spoofedMixinCounter"
        val bytes = loader.getResourceAsStream("$internalName.class")?.readBytes()
            ?: error("Could not find class for $internalName")

        val reader = ClassReader(bytes)
        val baseName = reader.className.substringAfterLast('/')
        val fullName = "$prefix/$baseName"
        val writer = LoaderClassWriter(loader, reader, ClassWriter.COMPUTE_FRAMES)

        reader.accept(LambdaAwareRemapper(writer, SimpleRemapper(reader.className, fullName)), 0)
        loader.injectResource("$fullName.class", writer.toByteArray())

        registerDynamicMixin(
            """
            {
                "required": true,
                "package": "${prefix.replace('/', '.')}",
                "minVersion": "0.8.5",
                "mixins": ["$baseName"],
                "injectors": {
                    "defaultRequire": 1
                }
            }
            """.trimIndent()
        )
    }

    private fun injectService(name: String, value: String) =
        loader.injectResource("META-INF/services/$name", value.encodeToByteArray())
}

internal sealed interface MixinAccess {
    fun bootstrap()
    fun validate()
    fun gotoDefault()
    fun addMixin(name: String)
    fun transform(transformer: Any, internalName: String, bytes: ByteArray): ByteArray
}

@Suppress("unused")
private data object MixinAccessImpl : MixinAccess {
    override fun bootstrap() = MixinBootstrap.init()
    override fun validate() {
        val service = MixinService.getService()
        require(service is SandboxedMixinService) { "Invalid mixin service: $service" }
    }

    override fun gotoDefault() {
        // Usually does nothing, but the internal state kind of expects this to be the case
        MixinEnvironment.init(Phase.DEFAULT)
        MixinEnvironment::class.java.getDeclaredMethod("gotoPhase", Phase::class.java)
            .also { it.isAccessible = true }(null, Phase.DEFAULT)
    }

    override fun addMixin(name: String) = Mixins.addConfiguration(name)
    override fun transform(transformer: Any, internalName: String, bytes: ByteArray): ByteArray =
        (transformer as IMixinTransformer).transformClass(MixinEnvironment.getDefaultEnvironment(), internalName, bytes)
}

private fun <R> counter() = object : ReadOnlyProperty<R, Int> {
    private var underlying = 0
    override fun getValue(thisRef: R, property: KProperty<*>) = underlying++
}