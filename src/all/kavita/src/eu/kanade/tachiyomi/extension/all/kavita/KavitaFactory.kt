package eu.kanade.tachiyomi.extension.all.kavita

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class KavitaFactory : SourceFactory {
    override fun createSources(): List<Source> {
        val progressAware = hostSupportsProgressAware()
        return listOf("1", "2", "3").map { suffix ->
            if (progressAware) {
                KavitaWithProgress(suffix)
            } else {
                Kavita(suffix)
            }
        }
    }

    companion object {
        /**
         * True when the host classpath includes ProgressAwareSource.
         * The subclass that implements the interface is only created in that case,
         * so old hosts never resolve it.
         */
        private fun hostSupportsProgressAware(): Boolean =
            try {
                Class.forName("eu.kanade.tachiyomi.source.ProgressAwareSource")
                true
            } catch (_: ClassNotFoundException) {
                false
            } catch (_: Throwable) {
                false
            }
    }
}
