package com.episode6.meetingminder.alarm

/** A [SoundCatalogSource] that hands back [catalog], counting the loads. */
internal class FakeSoundCatalogSource(private val catalog: SoundCatalog) : SoundCatalogSource {
    var loads = 0
        private set

    override suspend fun load(): SoundCatalog {
        loads++
        return catalog
    }
}
