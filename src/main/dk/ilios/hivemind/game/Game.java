package dk.ilios.hivemind.game;

import dk.ilios.hivemind.Constants;
import dk.ilios.hivemind.ai.statistics.GameStatistics;
import dk.ilios.hivemind.debug.HiveAsciiPrettyPrinter;
import dk.ilios.hivemind.model.Board;
import dk.ilios.hivemind.model.Hex;
import dk.ilios.hivemind.model.Player;
import dk.ilios.hivemind.model.rules.Rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the game state for a game of Hive
 */
public class Game {

    private static final boolean DEBUG = false;

    private String name = "HiveX";

    private Player whitePlayer;
    private Player blackPlayer;
    private Board board = new Board();

    private boolean isRunning = false;      // Game is started and progressing
    private boolean manualStepping = false; // If true, continue() must be called after every move to progress the game (for debugging/testing)
    private boolean replayMode = false;     // If true, the game cannot progress any futher, but forward(), backwards() can be called to navigate the game. When set to to false, game is forwarded to last position again.
    private int replayIndex = 0;            // Pointer to current move (that has not been played).

    private int turnLimit = -1;             // If above 0, the game ends in a draw after so many moves.

    private GameStatus status = GameStatus.RESULT_NOT_STARTED;
    private Player activePlayer;
    private List<GameCommand> moves = new ArrayList<GameCommand>();

    private boolean printGameStateAfterEachMove = false;
    private GameStatistics statistics = new GameStatistics();
    private HiveAsciiPrettyPrinter mapPrinter = new HiveAsciiPrettyPrinter();

    // Keeping track of draws
    private boolean forcedDraw;             // If true, game is declared a draw after a set number of repeat moves by each player.
    private int repeatMovesBeforeForcedDraw = 3; // Number of moves each player must make that are "the same" before forcing a draw.
    private int whiteDuplicateMoves = 0;
    private int blackDuplicateMoves = 0;


    public void addPlayers(Player white, Player black) {
        this.whitePlayer = white;
        this.blackPlayer = black;
        statistics.setWhiteName(whitePlayer.getName());
        statistics.setBlackName(blackPlayer.getName());
    }

    /**
     * Resets the game and start a new game.
     */
    public void start() {
        if (isRunning) return;
        board.clear();
        moves.clear();
        activePlayer = whitePlayer;

        // Start the game loop
        statistics.startGame();
        setStatus(GameStatus.RESULT_MATCH_IN_PROGRESS);
        isRunning = true;

        while (isRunning && !manualStepping) {
            continueGame();
        }
    }

    public void setReplayMode(boolean enabled) {
        if (replayMode == enabled) return;

        if (enabled) {
            // Enable replay mode and put replay index at the start of the game
            isRunning = false;
            replayMode = true;
            replayIndex = moves.size();
            while (replayIndex > 0) {
                backwards();
            }

        } else {
            // Disable replay mode and forward the game to last move.
            while (replayIndex < moves.size()) {
                forward();
            }
            isRunning = true;
            replayMode = false;
        }
    }

    public void forward() {
        if (!replayMode) throw new IllegalStateException("Replay mode not enabled");
        if (replayIndex == moves.size()) return;
        moves.get(replayIndex).execute(this);
        replayIndex++;
    }

    public void backwards() {
        if (!replayMode) throw new IllegalStateException("Replay mode not enabled");
        if (replayIndex == 0) return;
        replayIndex--;
        moves.get(replayIndex).undo(this);
    }

    /**
     * Move the game forward one step with the given command.
     * This overrides the CommandProvider if any i set
     * @param command
     */
    public void continueGame(GameCommand command) {
        if (!isRunning || replayMode) return;

        // Use CommandProvider if command is not provided
        if (command == null) {
            command = activePlayer.getCommandProvider().getCommand(this, board);
        }

        if (isLegalCommand(command)) {
            executeCommand(command);
            if (printGameStateAfterEachMove) {
                printBoardState(command);
            }

            if (isEndOfGame()) {
                if (DEBUG) System.out.println("Game over!");
                isRunning = false;
                statistics.stopGame();
            }

        } else {
            throw new IllegalStateException("Illegal command: " + activePlayer.getName() + " trying to execute " + command.toString());
        }
    }

    /**
     * Move the game forward one step.
     * Only call this if manual stepping is enabled.
     * Doesn't work in replay mode,
     */
    public void continueGame() {
        continueGame(null);
    }

    /**
     * Output the current board state to the screen
     */
    private void printBoardState(GameCommand command) {
        System.out.println(getOtherPlayer().getName() + ": Turn " + getOtherPlayer().getTurns());
        System.out.println("DuplicateMoves: " + whiteDuplicateMoves + " vs. " + blackDuplicateMoves);
        System.out.println(command.toString());
        mapPrinter.print(board);
    }

    private void executeCommand(GameCommand command) {
        if (command.equals(GameCommand.PASS)) {
            statistics.playerPasses(activePlayer);
        } else {
            statistics.playerMoves(activePlayer);
        }

        keepTrackOfDuplicateMoves(command);
        command.execute(this);
        moves.add(command);
    }

    /**
     * Keeps track of players executing duplicate moves.
     * Should be called before the move is actually executed.
     */
    private void keepTrackOfDuplicateMoves(GameCommand command) {
        if (moves.size() >= 4) {
            String lastMove = moves.get(moves.size() - 4).getTargetSquareDesc();
            if (command.getTargetSquareDesc().equals(lastMove)) {
                if (activePlayer.isWhitePlayer()) {
                    whiteDuplicateMoves++;
                } else {
                    blackDuplicateMoves++;
                }
            } else {
                if (activePlayer.isWhitePlayer()) {
                    whiteDuplicateMoves = 0;
                } else {
                    blackDuplicateMoves = 0;
                }
            }
        }
    }

    private boolean isLegalCommand(GameCommand command) {
        if (command == null) {
            throw new IllegalStateException("Command must not be null");
        }

        // Queen must be played at the 4 turn at the latest
        if (activePlayer.getMoves() == 3) {
            boolean queenOnBoard = activePlayer.hasPlacedQueen();
            if (!queenOnBoard) {
                boolean ok = activePlayer.getQueen().equals(command.getToken());
                if (!ok) {
                    if (DEBUG) System.out.println("Queen must be moved now, was: " + command);
                }
                return ok;
            }
        }

        // Hive must not move before queen has been placed
        if (!activePlayer.hasPlacedQueen()) {
            if (!command.equals(GameCommand.PASS) && command.getFromQ() != Hex.SUPPLY) {
                if (DEBUG) System.out.println("Queen must be moved before placing queen: " + command);
                return false;
            }
        }

        return true;
    }

    public boolean isEndOfGame() {
        boolean whiteQueenSurronded = Rules.getInstance().isQueenSurrounded(whitePlayer, board);
        boolean blackQueenSurronded = Rules.getInstance().isQueenSurrounded(blackPlayer, board);
        boolean forcedDraw = isForcedDraw();

        // End game conditions
        if (whitePlayer.getTurns() + blackPlayer.getTurns() == 2 * turnLimit && turnLimit > 0) {
            setStatus(GameStatus.RESULT_TURN_LIMIT_REACHED);
            if (DEBUG) System.out.println("Move limit reached: " + turnLimit);
            return true;
        } else if (whiteQueenSurronded && blackQueenSurronded) {
            setStatus(GameStatus.RESULT_DRAW);
            if (DEBUG) System.out.println("Game ended in a draw!");
            return true;
        } else if (whiteQueenSurronded) {
            setStatus(GameStatus.RESULT_BLACK_WINS);
            if (DEBUG) System.out.println("Black wins!");
            return true;
        } else if (blackQueenSurronded) {
            setStatus(GameStatus.RESULT_WHITE_WINS);
            if (DEBUG) System.out.println("White wins");
            return true;
        } else if (forcedDraw) {
            setStatus(GameStatus.RESULT_DRAW);
            if (DEBUG) System.out.println("Game ended in a draw!");
            return true;
        }

        // Continue game
        return false;
    }

    public Player getActivePlayer() {
        return activePlayer;
    }

    public Player getWhitePlayer() {
        return whitePlayer;
    }

    public Player getBlackPlayer() {
        return blackPlayer;
    }

    public Board getBoard() {
        return board;
    }

    public void setTurnLimit(int limit) {
        this.turnLimit = limit;
    }

    public int getTurnLimit() {
        return turnLimit;
    }

    public GameStatistics getStatistics() {
        return statistics;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
        this.statistics.setStatus(status);
    }

    public GameStatus getStatus() {
        return status;
    }

    public void togglePlayer() {
        if (activePlayer == null) {
            activePlayer = whitePlayer;
        } else {
            activePlayer = (activePlayer == whitePlayer) ? blackPlayer : whitePlayer;
        }
    }

    public void setActivePlayer(Player player) {
        if (!player.equals(whitePlayer) && !player.equals(blackPlayer)) {
            throw new IllegalStateException("Active player must eiter be white or black: " + player.getName());
        }
        activePlayer = player;
    }

    public Player getOtherPlayer() {
        return (activePlayer == whitePlayer) ? blackPlayer : whitePlayer;
    }

    public void setManualStepping(boolean manualStepping) {
        this.manualStepping = manualStepping;
    }

    public void setPrintGameStateAfterEachMove(boolean print) {
        this.printGameStateAfterEachMove = print;
    }

    /**
     * Return the move for the player at the given turn (for that player)
     * @param player    Black or white player
     * @param turn      1 - GameEnd
     * @returns GameCommand for that move or null if it doesn't exists.
     */
    public GameCommand getMove(Player player, int turn) {
        if (turn <= 0) return null;
        int turnIndex = (turn - 1)*2  + (player.isBlackPlayer() ? 1 : 0);
        return (moves.size() > turnIndex) ? moves.get(turnIndex) : null;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isForcedDraw() {
        int maxTurns = repeatMovesBeforeForcedDraw;
        return whiteDuplicateMoves >= maxTurns && blackDuplicateMoves >= maxTurns;
    }
}