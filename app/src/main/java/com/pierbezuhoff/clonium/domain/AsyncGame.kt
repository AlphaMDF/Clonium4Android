package com.pierbezuhoff.clonium.domain

import com.pierbezuhoff.clonium.utils.Logger
import com.pierbezuhoff.clonium.utils.StandardLogger
import com.pierbezuhoff.clonium.utils.impossibleCaseOf
import kotlinx.coroutines.*
import java.util.*
import kotlin.Comparator
import kotlin.math.min

class AsyncGame(
    override val board: EvolvingBoard,
    bots: Set<BotPlayer>,
    initialOrder: List<PlayerId>? = null,
    override val coroutineScope: CoroutineScope
) : Game {
    override val players: Map<PlayerId, Player>
    override val order: List<Player>
    override val lives: MutableMap<Player, Boolean>
    @Suppress("RedundantModalityModifier") // or else "error: property must be initialized or be abstract"
    final override var currentPlayer: Player
    private val linkedTurns: LinkedTurns

    init {
        initialOrder?.let {
            require(board.players() == initialOrder.toSet()) { "order is incomplete: $initialOrder instead of ${board.players()}" }
        }
        val playerIds = initialOrder ?: board.players().shuffled().toList()
        require(bots.map { it.playerId }.all { it in playerIds }) { "Not all bot ids are on the board" }
        val botIds = bots.map { it.playerId }
        val botMap = bots.associateBy { it.playerId }
        val humanMap = (playerIds - botIds).associateWith { HumanPlayer(it) }
        players = botMap + humanMap
        order = playerIds.map { players.getValue(it) }
        require(order.isNotEmpty()) { "AsyncGame should have players" }
        lives = order.associateWith { board.possOf(it.playerId).isNotEmpty() }.toMutableMap()
        require(lives.values.any()) { "Someone should be alive" }
        currentPlayer = order.first { isAlive(it) }
        linkedTurns = LinkedTurns(board, order.map { it.playerId }, players, coroutineScope)
    }

    constructor(gameState: Game.State, coroutineScope: CoroutineScope) : this(
        PrimitiveBoard(gameState.board),
        gameState.bots
            .map { (playerId, tactic) -> tactic.toPlayer(playerId) }
            .toSet(),
        gameState.order,
        coroutineScope
    )

    private fun makeTurn(turn: Pos, trans: Trans): Sequence<Transition> {
        require(turn in possibleTurns())
        val transitions = board.incAnimated(turn)
        require(board == trans.board)
        require(transitions == trans.transitions)
        lives.clear()
        order.associateWithTo(lives) { board.possOf(it.playerId).isNotEmpty() }
        currentPlayer = nextPlayer()
        require(currentPlayer.playerId == trans.order.first())
        return transitions
    }

    override fun humanTurn(pos: Pos): Sequence<Transition> {
        require(currentPlayer is HumanPlayer)
        val trans = linkedTurns.givenHumanTurn(pos)
        return makeTurn(pos, trans)
    }

    override fun botTurnAsync(): Deferred<Sequence<Transition>> {
        require(currentPlayer is BotPlayer)
        return coroutineScope.async(Dispatchers.Default) {
            val (pos, trans) = linkedTurns.requestBotTurnAsync().await()
            return@async makeTurn(pos, trans)
        }
    }

    object Builder : Game.Builder {
        override fun of(gameState: Game.State, coroutineScope: CoroutineScope): Game =
            AsyncGame(gameState, coroutineScope)
    }
}

class G(
    val game: Game = run {
        val board = BoardFactory.spawn4players(EmptyBoardFactory.SMALL_TOWER)
        val bots: Set<BotPlayer> =
            setOf(
//                RandomPickerBot(PlayerId0),
                LevelMaximizerBot(PlayerId0, depth = 1),
                RandomPickerBot(PlayerId1),
//                RandomPickerBot(PlayerId2),
//                ChipCountMaximizerBot(PlayerId2, depth = 1),
                RandomPickerBot(PlayerId3)
            )
        val order = listOf(PlayerId0, PlayerId1, PlayerId2, PlayerId3)
        SimpleGame(PrimitiveBoard(board), bots, order, GlobalScope)
    }
) {
    private val linkedTurns = LinkedTurns(
        PrimitiveBoard(game.board),
        game.order.map { it.playerId },
        game.players,
        GlobalScope
    )

    val f get() = println(linkedTurns.focus)
    val w: Int get() = linkedTurns.computeWidth()
    val d: Int? get() = linkedTurns.computeDepth()
    val c get() = println(linkedTurns.computing)
    val sc get() = println(linkedTurns.scheduledComputings.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]"))
    val u get() = println(linkedTurns.unknowns.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]"))

    fun h(turn: Pos) {
        linkedTurns.givenHumanTurn(turn)
        println(linkedTurns.focus)
    }

    fun r() {
        linkedTurns.requestBotTurnAsync()
        println(linkedTurns.focus)
    }

    fun d() {
        linkedTurns.discoverUnknowns()
        println(linkedTurns.focus)
    }
}

@Suppress("unused")
private class LinkedTurns(
    board: EvolvingBoard,
    order: List<PlayerId>,
    private val players: Map<PlayerId, Player>,
    private val coroutineScope: CoroutineScope
) : Logger by StandardLogger {
    internal var computing: NextAs<Link.FutureTurn.Bot.Computing>? = null
    // MAYBE: use PriorityBlockingQueue
    internal val scheduledComputings: PriorityQueue<NextAs<Link.FutureTurn.Bot.ScheduledComputing>> =
        PriorityQueue(1, NextAsDepthComparator())
    internal val unknowns: PriorityQueue<NextAs<Link.Unknown>> =
        PriorityQueue(10, NextAsDepthComparator())
    private val start: Link.Start = Link.Start(nextUnknown(Trans(board, order, emptySequence()), depth = 1))
    internal val focus: Link get() = start.next.link
    private var nOfTraversedTurns = 0

    init {
        require(order.isNotEmpty())
        if (order.size == 1) {
            unknowns.clear()
            start.next.link = Link.End
        } else {
            discoverUnknowns()
        }
    }

    private fun Next.scheduleTurn(depth: Int) {
        val board = trans.board
        val order = trans.order
        require(order.isNotEmpty())
        log("${this}\n.scheduleTurn(depth = $depth)\n")
        val playerId = order.first()
        when (val player = players.getValue(playerId)) {
            is HumanPlayer -> {
                val possibleTurns = board.possOf(player.playerId)
                link = Link.FutureTurn.Human.OneOf(
                    player.playerId,
                    // TODO: connect chained turns' unknowns (as in scheduleTurnsAfterHuman)
                    possibleTurns.associateWith { turn ->
                        val nextBoard = board.copy()
                        val transitions = nextBoard.incAnimated(turn)
                        val nextOrder = nextBoard.shiftOrder(order)
                        val trans = Trans(nextBoard, nextOrder, transitions)
                        return@associateWith if (nextOrder.size <= 1)
                            Next(trans, Link.End)
                        else
                            nextUnknown(trans, depth + 1)
                    }
                )
            }
            is BotPlayer ->
                scheduleComputation(player, board, order, depth = depth)
            else -> impossibleCaseOf(player)
        }
        log("scheduleTurn -> \n$link\n")
    }

    private fun nextUnknown(trans: Trans, depth: Int): Next {
        val unknown = Link.Unknown(depth)
        val nextAs = NextAs(trans, unknown)
        unknowns.add(nextAs)
        return nextAs.next
    }

/*
    private fun scheduleTurnsAfterHuman(rootBoard: EvolvingBoard, root: Link.FutureTurn.Human.OneOf) {
        log("scheduleTurnsAfterHuman($rootBoard,\n$root\n)\n")
        val (chained, free) =
            root.nexts.entries.partition { rootBoard.levelAt(it.key) == Level3 }
        for ((_, next) in free) {
            rescheduleUnknownFutureTurn(next)
        }
        val turns = chained.map { it.key }
        val chains = rootBoard.chains().toList()
        val chainIds = turns.associateWith { turn -> chains.indexOfFirst { turn in it } }
        val chainedLinks: Map<Int, Link> = chains
            .mapIndexed { id, chain ->
                id to rescheduleUnknownFutureTurn(root.nexts.getValue(chain.first()))
            }.toMap()
        for ((turn, next) in chained) {
            // turns in the same chain have common next.link
            next.link = chainedLinks.getValue(chainIds.getValue(turn))
        }
    }
*/

    private fun Next.scheduleComputation(bot: BotPlayer, board: EvolvingBoard, order: List<PlayerId>, depth: Int) {
        log("scheduleComputation($bot, $board,\n$order, depth = $depth)\n")
        val computation = Computation(bot, board, order, depth)
        synchronized(ComputingLock) {
            if (computing == null) {
                val newComputing = Link.FutureTurn.Bot.Computing(
                    bot.playerId, depth, computation, runComputationAsync(computation)
                )
                computing = NextAs(this, newLink = newComputing)
            } else {
                val scheduledComputing = Link.FutureTurn.Bot.ScheduledComputing(bot.playerId, depth, computation)
                scheduledComputings.add(NextAs(this, scheduledComputing))
            }
            Unit
        }
    }

    private fun runComputationAsync(computation: Computation): Deferred<Link.FutureTurn.Bot.Computed> =
        with(computation) { coroutineScope.runAsync { runNext(it) } }

    private fun runNext(computed: Link.FutureTurn.Bot.Computed) {
        log("runNext(\n$computed\n)\n")
        synchronized(ComputingLock) {
            require(computing != null)
            computing!!.next.link = computed
            computing = null
        }
        // add external unknown (from Computation.runAsync)
        unknowns.add(NextAs(computed.next, computed.next.link as Link.Unknown))
        log("runNext(computed) =>\nfocus = $focus\n")
        runNext()
    }

    private fun runNext() {
        synchronized(ComputingLock) {
            require(computing == null)
            scheduledComputings.poll()?.let { scheduledNextAs ->
                val newComputing = with(scheduledNextAs.link) {
                    Link.FutureTurn.Bot.Computing(
                        playerId, depth, computation,
                        runComputationAsync(computation)
                    )
                }
                log("new computing =\n$newComputing\n")
                computing = NextAs(scheduledNextAs.next, newLink = newComputing)
            } ?: discoverUnknowns()
        }
    }

    internal fun discoverUnknowns() {
        log("discoverUnknowns()\n")
        while (unknowns.isNotEmpty() && computeWidth() < SOFT_MAX_WIDTH) {
            val nextAs = unknowns.remove()
            nextAs.next.scheduleTurn(nextAs.link.depth)
        }
        if (computeWidth() >= SOFT_MAX_WIDTH)
            log("SOFT_MAX_WIDTH = $SOFT_MAX_WIDTH hit while discovering unknowns\n")
    }

    fun givenHumanTurn(turn: Pos): Trans {
        require(focus is Link.FutureTurn.Human.OneOf)
        val focus = focus as Link.FutureTurn.Human.OneOf
        require(turn in focus.nexts.keys)
        val (trans, nextFocus) = focus.nexts.getValue(turn)
        start.next.link = nextFocus
        nOfTraversedTurns ++
        collapseComputations(focus, turn)
        discoverUnknowns()
        return trans
    }

    private fun collapseComputations(root: Link.FutureTurn.Human.OneOf, actualTurn: Pos) {
        log("collapseComputations(\n$root,\nactualTurn = $actualTurn)\n")
        for ((turn, next) in root.nexts)
            if (turn != actualTurn)
                next.stopComputations()
        synchronized(ComputingLock) {
            if (computing == null) // when it was stopped
                runNext()
        }
    }

    private fun Next.stopComputations() {
        val nextAs = NextAs(this, link)
        when (val root = nextAs.link) {
            is Link.FutureTurn.Bot.ScheduledComputing -> {
                scheduledComputings.remove(nextAs)
                link = Link.Unknown(root.depth) // do not add to unknowns, obsolete branch
            }
            is Link.FutureTurn.Bot.Computing -> {
                synchronized(ComputingLock) {
                    require(computing == nextAs)
                    computing = null
                }
                root.deferred.cancel()
                link = Link.Unknown(root.depth)
            }
            is Link.FutureTurn.Human.OneOf ->
                for ((_, next) in root.nexts)
                    next.stopComputations()
            is Link.FutureTurn.Bot.Computed ->
                root.next.stopComputations()
        }
    }

    fun requestBotTurnAsync(): Deferred<Pair<Pos, Trans>> {
        require(focus is Link.FutureTurn.Bot)
        return when (val focus = focus as Link.FutureTurn.Bot) {
            is Link.FutureTurn.Bot.Computed -> {
                start.next.link = focus.next.link
                nOfTraversedTurns ++
                return coroutineScope.async { focus.pos to focus.next.trans }
            }
            is Link.FutureTurn.Bot.Computing -> coroutineScope.async {
                val computed = focus.deferred.await()
                start.next.link = computed.next.link
                nOfTraversedTurns ++
                return@async computed.pos to computed.next.trans
            }
            else -> impossibleCaseOf(focus)
        }
    }

    /** Depth of [link] + [depth], `null` means infinite: all possibilities are computed */
    internal fun computeDepth(link: Link = focus, depth: Int = nOfTraversedTurns): Int? =
        when (link) {
            is Link.End -> null
            is Link.TemporalTerminal -> depth
            is Link.FutureTurn.Bot.Computed -> computeDepth(link.next.link, depth + 1)
            is Link.FutureTurn.Human.OneOf -> link.nexts.values
                .map { next -> computeDepth(next.link, depth + 1) }
                .fold(null as Int?) { d0: Int?, d1: Int? ->
                    if (d0 == null) d1 else if (d1 == null) d0 else min(d0, d1)
                }
            else -> impossibleCaseOf(link)
        }

    internal fun computeWidth(root: Link = focus): Int =
        when (root) {
            is Link.FutureTurn.Bot.Computed -> computeWidth(root.next.link)
            is Link.FutureTurn.Human.OneOf -> root.nexts.values.sumBy { computeWidth(it.link) }
            is Link.Terminal -> 1
            else -> impossibleCaseOf(root)
        }

    private object ComputingLock
    private class NextAsDepthComparator<L> : Comparator<NextAs<L>> where L : Link.WithDepth, L : Link {
        override fun compare(o1: NextAs<L>, o2: NextAs<L>): Int =
            o1.link.depth.compareTo(o2.link.depth)
    }
    companion object {
        private const val SOFT_MAX_WIDTH = 20
    }
}

private sealed class Link {
    interface Transient // has next or nexts
    interface Terminal
    interface TemporalTerminal : Terminal
    interface WithDepth { val depth: Int }

    class Start(
        val next: Next
    ) : Link(), Transient {
        override fun toString(indent: Int) =
            indentOf(indent) + "Start:\n" + next.toString(indent + 1)
    }

    object End : Link(), Terminal {
        override fun toString() =
            "End"
    }

    class Unknown(
        override val depth: Int
    ) : Link(), TemporalTerminal, WithDepth {
        override fun toString() =
            "Unknown(depth = $depth)"
    }

    sealed class FutureTurn(
        val playerId: PlayerId
    ) : Link() {

        sealed class Human(
            playerId: PlayerId
        ) : FutureTurn(playerId) {
            class OneOf(
                playerId: PlayerId,
                val nexts: Map<Pos, Next>
            ) : Human(playerId), Transient {
                override fun toString(indent: Int) =
                    indentOf(indent) + "Human.OneOf($playerId):" + nexts.entries.joinToString(
                        prefix = "\n${indentOf(indent + 1)}[\n",
                        separator = ",\n",
                        postfix = "\n${indentOf(indent + 1)}]"
                    ) { (pos, next) -> indentOf(indent + 1) + "$pos ->\n${next.toString(indent + 2)}" }
            }
        }

        sealed class Bot(
            playerId: PlayerId
        ) : FutureTurn(playerId) {
            class Computed(
                playerId: PlayerId,
                val pos: Pos,
                val next: Next
            ) : Bot(playerId), Transient {
                override fun toString(indent: Int) =
                    indentOf(indent) + "Bot.Computed($playerId, $pos):\n" + next.toString(indent + 1)
            }
            class Computing(
                playerId: PlayerId,
                override val depth: Int,
                val computation: Computation,
                val deferred: Deferred<Computed>
            ) : Bot(playerId), TemporalTerminal, WithDepth {
                override fun toString(): String =
                    "Bot.Computing($playerId, depth = $depth, $computation)"
            }
            class ScheduledComputing(
                playerId: PlayerId,
                override val depth: Int,
                val computation: Computation
            ) : Bot(playerId), TemporalTerminal, WithDepth {
                override fun toString(): String =
                    "Bot.ScheduledComputing($playerId, depth = $depth, $computation)"
            }
        }
    }

    internal open fun toString(indent: Int): String =
        indentOf(indent) + toString()
    internal fun indentOf(indent: Int): String =
        "  ".repeat(indent)
    override fun toString(): String =
        toString(0)
}

private data class Next(
    val trans: Trans,
    var link: Link
) {
    fun toString(indent: Int): String =
        link.toString(indent)

    override fun toString(): String =
        toString(0)
}

private class NextAs<out L : Link>(
    val next: Next,
    newLink: L
) {
    /** Typed next.link */
    val link: L = newLink
    init {
        next.link = link
    }

    constructor(trans: Trans, link: L) : this(Next(trans, link), link)

    override fun equals(other: Any?) =
        other is NextAs<*> && next == other.next
    override fun hashCode() =
        next.hashCode()
    override fun toString() =
        next.toString()
}

private data class Trans(
    val board: EvolvingBoard,
    val order: List<PlayerId>,
    val transitions: Sequence<Transition>
)

private data class Computation(
    private val bot: BotPlayer,
    private val board: EvolvingBoard,
    private val order: List<PlayerId>,
    private val depth: Int
) {
    inline fun CoroutineScope.runAsync(
        crossinline andThen: (Link.FutureTurn.Bot.Computed) -> Unit = {}
    ): Deferred<Link.FutureTurn.Bot.Computed> =
        async {
            print("(...")
            val startTime = System.currentTimeMillis()
            val turn = with(bot) { makeTurnAsync(board, order).await() }
            val elapsed = System.currentTimeMillis() - startTime
            print("$elapsed ms)")
            val endBoard = board.copy()
            val transitions = endBoard.incAnimated(turn)
            val endOrder = endBoard.shiftOrder(order)
            val trans = Trans(endBoard, endOrder, transitions)
            val nextLink: Link = if (endOrder.size <= 1) Link.End else Link.Unknown(depth + 1)
            val computed = Link.FutureTurn.Bot.Computed(
                bot.playerId, turn, Next(trans, nextLink)
            )
            andThen(computed)
            computed
        }
}

