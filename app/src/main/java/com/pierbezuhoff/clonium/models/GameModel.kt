package com.pierbezuhoff.clonium.models

import android.graphics.Canvas
import android.graphics.PointF
import com.pierbezuhoff.clonium.domain.*
import com.pierbezuhoff.clonium.ui.game.DrawThread
import com.pierbezuhoff.clonium.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.KoinComponent
import org.koin.core.get

// MAYBE: non-significant explosions are non-blocking
// MAYBE: issue pre-turn (BoardPresenter.boardHighlighting.showNextTurn)
class GameModel(
    val game: Game,
    private val config: GameConfig,
    chipsConfig: ChipsConfig,
    private val coroutineScope: CoroutineScope
) : Any()
    , DrawThread.Callback
    , Logger by AndroidLoggerOf<GameModel>()
    , KoinComponent
{
    interface StatHolder { fun updateStat(gameStat: GameStat) }
    private val statHolderConnection = Connection<StatHolder>()
    val statUpdatingSubscription = statHolderConnection.subscription

    interface CurrentPlayerHolder { fun updateCurrentPlayer(player: Player) }
    private val currentPlayerHolderConnection = Connection<CurrentPlayerHolder>()
    val currentPlayerUpdatingSubscription = currentPlayerHolderConnection.subscription

    private val gamePresenter: GamePresenter = get<GamePresenter.Builder>().of(game, chipsConfig, margin = 1f)
    private var continueGameOnce by Once(true)

    fun userTap(point: PointF) {
        synchronized(Lock) {
            if (/*!game.isEnd() && */!gamePresenter.blocking && game.currentPlayer is HumanPlayer) {
                val pos = gamePresenter.pointf2pos(point)
                if (pos in game.possibleTurns()) {
                    gamePresenter.boardHighlighting.hidePossibleTurns()
                    gamePresenter.freezeBoard()
                    if (game.isEnd())
                        game.board
                    val transitions = game.humanTurn(pos)
                    gamePresenter.boardHighlighting.showLastTurn(pos, game.nPlayers)
                    gamePresenter.startTransitions(transitions)
                    gamePresenter.unfreezeBoard()
                    continueGameOnce = true
                }
            } // MAYBE: if selfNext is human => issue pre-turn
        }
    }

    fun setSize(width: Int, height: Int) =
        gamePresenter.setSize(width, height)

    override fun draw(canvas: Canvas) =
        synchronized(Lock) {
            gamePresenter.draw(canvas)
        }

    override fun advance(timeDelta: Long) {
        synchronized(Lock) {
            gamePresenter.advance((config.gameSpeed * timeDelta).toLong())
            if (!gamePresenter.blocking && continueGameOnce)
                continueGame()
        }
    }

    private fun continueGame() { // synchronized(Lock) from advance
        statHolderConnection.send {
            updateStat(game.stat())
        }
        when {
            game.isEnd() -> {
                // TODO: show overall stat
            }
            game.currentPlayer is BotPlayer -> {
                gamePresenter.boardHighlighting.showBotPossibleTurns(game.possibleTurns())
                currentPlayerHolderConnection.send {
                    updateCurrentPlayer(game.currentPlayer)
                }
                gamePresenter.freezeBoard()
                coroutineScope.launch {
                    delay(config.botMinTime)
                    val (turn, transitions) = game.botTurn()
                    synchronized(Lock) {
                        gamePresenter.boardHighlighting.hidePossibleTurns()
                        gamePresenter.boardHighlighting.showLastTurn(turn, game.nPlayers)
                        gamePresenter.startTransitions(transitions)
                        gamePresenter.unfreezeBoard()
                        continueGameOnce = true
                    }
                }
            }
            else -> {
                // before human's turn:
                gamePresenter.boardHighlighting.showHumanPossibleTurns(game.possibleTurns())
                currentPlayerHolderConnection.send {
                    updateCurrentPlayer(game.currentPlayer)
                }
            }
        }
    }

    object Lock
}