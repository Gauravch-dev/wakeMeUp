package com.laststop.app

import java.util.PriorityQueue
import kotlin.math.*

data class Station(val id: String, val name: String, val lat: Double, val lon: Double, val aliases: String = "")
data class Fix(val lat: Double, val lon: Double, val accuracy: Double, val elapsedMs: Long)
data class Projection(val progress: Double, val offset: Double)
data class Corridor(val id: String, val name: String, val stationIds: List<String>)
data class JourneyLeg(val corridorId: String, val lineName: String, val route: RailwayRoute) {
    val wakeStation get() = route.stations[route.stations.lastIndex - 1]
    val wakeAtSource get() = route.stations.size == 2
    // A small approach allowance around the previous station, not a user-selected
    // distance from the destination. Travel-order progress also catches skipped fixes.
    val wakeProgressMeters get() = (route.cumulative[route.stations.lastIndex - 1] - 300.0).coerceAtLeast(0.0)
}
data class JourneyPlan(val legs: List<JourneyLeg>) {
    init { require(legs.isNotEmpty()) }
    val source get() = legs.first().route.stations.first()
    val destination get() = legs.last().route.stations.last()
    val id get() = legs.joinToString("|") { "${it.corridorId}:${it.route.stations.first().id}:${it.route.stations.last().id}" }
    val changes get() = legs.dropLast(1).map { it.route.stations.last() }
    val totalMeters get() = legs.sumOf { it.route.cumulative.last() }
    val description get() = if (changes.isEmpty()) "Direct corridor · ${legs.first().lineName}"
        else "Change at ${changes.joinToString(" → ") { it.name }}"
}

/** Station-centre segments are an approximate corridor, not surveyed track geometry. */
class RailwayRoute(val stations: List<Station>) {
    init { require(stations.size >= 2); require(stations.map { it.id }.distinct().size == stations.size) }
    val cumulative: List<Double> = buildList {
        add(0.0)
        stations.zipWithNext().forEach { (a, b) -> add(last() + distance(a.lat, a.lon, b.lat, b.lon)) }
    }
    fun project(lat: Double, lon: Double): Projection {
        val scaleX = 111_195.0 * cos(Math.toRadians(lat))
        val scaleY = 111_195.0
        return stations.zipWithNext().mapIndexed { index, (a, b) ->
            val ax = (a.lon - lon) * scaleX
            val ay = (a.lat - lat) * scaleY
            val dx = (b.lon - a.lon) * scaleX
            val dy = (b.lat - a.lat) * scaleY
            val fraction = (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            Projection(cumulative[index] + fraction * (cumulative[index + 1] - cumulative[index]),
                hypot(ax + fraction * dx, ay + fraction * dy))
        }.minBy { it.offset }
    }
    fun simulatedFix(progress: Double, elapsedMs: Long): Fix {
        val clamped = progress.coerceIn(0.0, cumulative.last())
        val index = (0 until stations.lastIndex).firstOrNull { clamped <= cumulative[it + 1] }
            ?: stations.lastIndex - 1
        val a = stations[index]
        val b = stations[index + 1]
        val fraction = (clamped - cumulative[index]) / (cumulative[index + 1] - cumulative[index])
        return Fix(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction, 15.0, elapsedMs)
    }
    companion object {
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}

object MumbaiNetwork {
    val stations = MumbaiNetworkData.stations.sortedBy { it.name }
    val byId = stations.associateBy { it.id }
    val corridors = MumbaiNetworkData.corridors
    val defaultPlan by lazy { plans("PNVL", "VSH").first() }
    fun station(id: String) = byId.getValue(id)
    fun linesFor(id: String) = corridors.filter { id in it.stationIds }.map { it.name }.distinct()
    fun search(query: String): List<Station> {
        val key = query.trim().lowercase().replace(" ", "")
        return stations.filter { "${it.name} ${it.id} ${it.aliases}".lowercase().replace(" ", "").contains(key) }
    }
    fun plans(source: String, destination: String): List<JourneyPlan> {
        if (source == destination || source !in byId || destination !in byId) return emptyList()
        val direct = corridors.mapNotNull { corridor ->
            val a = corridor.stationIds.indexOf(source)
            val b = corridor.stationIds.indexOf(destination)
            if (a < 0 || b < 0) null else {
                val ids = if (a < b) corridor.stationIds.subList(a, b + 1)
                    else corridor.stationIds.subList(b, a + 1).reversed()
                JourneyPlan(listOf(leg(corridor.id, ids)))
            }
        }
        val alternative = shortestPlan(source, destination)
        return (direct + listOfNotNull(alternative))
            .distinctBy { plan -> plan.legs.flatMap { it.route.stations }.joinToString(",") { it.id } }
            .sortedWith(compareBy<JourneyPlan> { it.legs.size }.thenBy { it.totalMeters })
            .take(4)
    }
    private fun leg(corridorId: String, ids: List<String>): JourneyLeg {
        val corridor = corridors.first { it.id == corridorId }
        return JourneyLeg(corridor.id, corridor.name, RailwayRoute(ids.map(::station)))
    }
    private data class Node(val stationId: String, val corridorId: String)
    private data class Edge(val to: String, val corridor: String, val meters: Double)
    private data class Visit(val node: Node, val changes: Int, val meters: Double)
    private val adjacency by lazy {
        val edges = mutableMapOf<String, MutableList<Edge>>()
        corridors.forEach { line ->
            line.stationIds.zipWithNext().forEach { (a, b) ->
                val from = station(a); val to = station(b)
                val meters = RailwayRoute.distance(from.lat, from.lon, to.lat, to.lon)
                edges.getOrPut(a) { mutableListOf() }.add(Edge(b, line.id, meters))
                edges.getOrPut(b) { mutableListOf() }.add(Edge(a, line.id, meters))
            }
        }
        edges
    }
    // Minimise changes first, then distance. A corridor is not a timetable or a promised through service.
    private fun shortestPlan(source: String, destination: String): JourneyPlan? {
        val comparator = compareBy<Visit> { it.changes }.thenBy { it.meters }
        val queue = PriorityQueue(comparator)
        val start = Node(source, "")
        val best = mutableMapOf(start to Visit(start, 0, 0.0))
        val previous = mutableMapOf<Node, Node>()
        queue.add(best.getValue(start))
        var finish: Node? = null
        while (queue.isNotEmpty()) {
            val visit = queue.remove()
            if (best[visit.node] != visit) continue
            if (visit.node.stationId == destination) { finish = visit.node; break }
            adjacency[visit.node.stationId].orEmpty().forEach { edge ->
                val node = Node(edge.to, edge.corridor)
                val changes = visit.changes + if (visit.node.corridorId.isNotEmpty() && visit.node.corridorId != edge.corridor) 1 else 0
                val next = Visit(node, changes, visit.meters + edge.meters)
                val old = best[node]
                if (old == null || comparator.compare(next, old) < 0) {
                    best[node] = next; previous[node] = visit.node; queue.add(next)
                }
            }
        }
        val end = finish ?: return null
        val path = mutableListOf(end)
        while (path.last() != start) path.add(previous.getValue(path.last()))
        path.reverse()
        val legs = mutableListOf<JourneyLeg>()
        var line = path[1].corridorId
        var ids = mutableListOf(source)
        path.drop(1).forEachIndexed { index, node ->
            if (node.corridorId != line) {
                legs.add(leg(line, ids))
                ids = mutableListOf(path[index].stationId)
                line = node.corridorId
            }
            ids.add(node.stationId)
        }
        legs.add(leg(line, ids))
        return JourneyPlan(legs)
    }
}
