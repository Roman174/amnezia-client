package org.amnezia.vpn.util.net

class PhysicalNetworkTracker<K> {
    private val networks = mutableMapOf<K, Candidate>()
    @Volatile
    var selected: K? = null
        private set
    var epoch: Long = 0
        private set

    fun available(network: K) {
        networks.putIfAbsent(network, Candidate())
    }

    fun capabilities(
        network: K,
        usable: Boolean,
        priority: Int,
    ): Boolean {
        val candidate = networks.getOrPut(network) {
            Candidate()
        }
        candidate.usable = usable
        candidate.priority = priority
        return select()
    }

    fun blocked(
        network: K,
        blocked: Boolean,
    ): Boolean {
        networks.getOrPut(network) {
            Candidate()
        }.blocked = blocked
        return select()
    }

    fun lost(network: K): Boolean {
        networks.remove(network)
        return select()
    }

    fun clear() {
        networks.clear()
        selected = null
        epoch++
    }

    private fun select(): Boolean {
        val candidates = networks.filterValues {
            it.usable && !it.blocked
        }
        val bestPriority = candidates.values.maxOfOrNull { it.priority }
        val next = if (bestPriority != null && selected != null && candidates[selected]?.priority == bestPriority) {
            selected
        } else {
            candidates.entries.firstOrNull { it.value.priority == bestPriority }?.key
        }
        if (next == selected) {
            return false
        }
        selected = next
        epoch++
        return true
    }

    private data class Candidate(
        var usable: Boolean = false,
        var blocked: Boolean = false,
        var priority: Int = 0,
    )
}
