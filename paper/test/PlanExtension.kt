import com.djrapitops.plan.extension.extractor.ExtensionExtractor
import mc.xalitoria.chat.paper.external.plan.PlanExtension
import kotlin.test.Test
import kotlin.test.assertEquals

/** Plan's own check of an extension: annotations, parameters, return types and conditions. */
class PlanExtension {
    private val extension = PlanExtension::class.java.getDeclaredConstructor(mc.xalitoria.chat.paper.XaliChat::class.java)

    @Test
    fun planAcceptsTheExtension() {
        // The check only reads the class, so a plugin that is never used will do.
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val plugin = (unsafe as sun.misc.Unsafe).allocateInstance(mc.xalitoria.chat.paper.XaliChat::class.java)
        val instance = extension.newInstance(plugin as mc.xalitoria.chat.paper.XaliChat)
        ExtensionExtractor(instance).validateAnnotations()
        assertEquals("XaliChat", instance.pluginName)
    }
}
