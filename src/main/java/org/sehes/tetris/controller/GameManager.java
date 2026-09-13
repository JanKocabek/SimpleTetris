package org.sehes.tetris.controller;

import org.sehes.tetris.config.GhostType;
import org.sehes.tetris.controller.input.InputAction;
import org.sehes.tetris.model.BoardView;
import org.sehes.tetris.model.DirectionFlag;
import org.sehes.tetris.model.GameBoard;
import org.sehes.tetris.model.PieceGenerator;
import org.sehes.tetris.model.RotationFlag;
import org.sehes.tetris.model.Tetromino;
import org.sehes.tetris.model.TetrominoType;
import org.sehes.tetris.model.score.HardDropEvent;
import org.sehes.tetris.model.score.LockPieceEvent;
import org.sehes.tetris.model.score.SoftDropEvent;
import org.sehes.tetris.model.score.TSpin;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.sehes.tetris.controller.GameState.GAME_OVER;
import static org.sehes.tetris.controller.GameState.INIT;
import static org.sehes.tetris.controller.GameState.NEW_GAME;
import static org.sehes.tetris.controller.GameState.PAUSED;
import static org.sehes.tetris.controller.GameState.PLAYING;
import static org.sehes.tetris.controller.GameState.PREPARED;

public class GameManager implements InputHandler {

    // =========================================================================
    // 1. CONSTANTS
    // =========================================================================
    private static final int BASE_SPEED_MS = 600;
    private static final long movementSpeed = TimeUnit.MILLISECONDS.toNanos(BASE_SPEED_MS);


    // =========================================================================
    // 2. INFRASTRUCTURE & SERVICES
    // =========================================================================
    private final StateManager<GameState> stateManager;
    private final PieceGenerator generator;
    private final ScoreMessenger scoreMessenger;
    private final GameLoop gameLoop;
    private Rendering tetrisCanvas;
    private Runnable gameExit = () -> System.exit(0);
    private final LockDelay lockDelay = new LockDelay();
    // =========================================================================
    // 3. OBSERVABLES & OBSERVERS
    // =========================================================================
    private final Observable.Publisher<TetrominoType> spawnObservable = new ObservableImpl<>();
    private final Observable.Publisher<TetrominoType> holdObservable = new ObservableImpl<>();
    private final Observer<Long> tickObserver = this::onTickUpdate;

    // =========================================================================
    // 4. GAME MODEL & PIECE STATE
    // =========================================================================
    private GameBoard gameBoard;
    private GhostType ghostType = GhostType.FULL;
    private TetrominoType holdTetromino = null;
    private boolean isHoldLock = false;

    // =========================================================================
    // 5. TIMING, PHYSICS & RENDER STATE
    // =========================================================================
    private final AtomicBoolean isDirty = new AtomicBoolean(false);
    private long gravityAccumulator;
    // =========================================================================
    // PUBLIC INTERFACE (CONSTRUCTOR & PUBLIC METHODS)
    // =========================================================================

    public GameManager(StateManager<GameState> stateManager, ScoreMessenger scoreMessenger, PieceGenerator generator, GameLoop loop) {
        this.generator = generator;
        this.stateManager = stateManager;
        this.scoreMessenger = scoreMessenger;
        this.gameLoop = loop;
    }

    public void prepareGame(Rendering canvas, Runnable exitAction) {
        if (stateManager.getState() == INIT) {
            this.tetrisCanvas = canvas;
            gameExit = exitAction;
            stateManager.setState(PREPARED);
        }
    }

    @Override
    public void handleInput(InputAction action) {
        switch (stateManager.getState()) {
            case PREPARED -> preparedInput(action);
            case PLAYING -> runningGameInput(action);
            case PAUSED -> pauseGameInput(action);
            case GAME_OVER -> gameOverInput(action);
            default -> {
                break;
            }
        }
    }

    public Observer<Long> tickObserver() {
        return tickObserver;
    }

    public Observable<TetrominoType> spawnObservable() {
        return spawnObservable;
    }

    public Observable<TetrominoType> holdObservable() {
        return holdObservable;
    }

    // =========================================================================
    // PRIVATE METHODS: 1. INPUT ROUTING
    // =========================================================================

    private void runningGameInput(InputAction action) {
        switch (action) {
            case CANCEL -> exitGame();
            case CONFIRM -> pauseGame();
            case MOVE_DOWN -> softDrop();
            case HARD_DROP -> hardDrop();
            case MOVE_LEFT -> movePiece(DirectionFlag.LEFT);
            case MOVE_RIGHT -> movePiece(DirectionFlag.RIGHT);
            case ROTATE_CW -> rotatePiece(RotationFlag.CLOCKWISE);
            case ROTATE_CCW -> rotatePiece(RotationFlag.COUNTER_CLOCKWISE);
            case TOGGLE_GHOST -> toggleGhostPiece();
            case HOLD -> holdOrSwap();
            default -> {
                break;
            }
        }
    }

    private void preparedInput(InputAction action) {
        switch (action) {
            case CONFIRM -> startGame();
            case CANCEL -> exitGame();
            default -> {
                break;
            }
        }
    }

    private void pauseGameInput(InputAction action) {
        switch (action) {
            case CANCEL -> exitGame();
            case CONFIRM -> resumeGame();
            default -> {
                break;
            }
        }
    }

    private void gameOverInput(InputAction action) {
        switch (action) {
            case CONFIRM -> startGame();
            case CANCEL -> exitGame();
            default -> {
                break;
            }
        }
    }

    // =========================================================================
    // PRIVATE METHODS: 2. PIECE MOVEMENT & ACTIONS
    // =========================================================================

    private void movePiece(final DirectionFlag direction) {
        if (gameBoard.tryMovePiece(direction)) {
            checkLockDelay();
            render();
        }
    }

    private void checkLockDelay() {
        lockDelay.checkMove(getCurrentTetromino().getPositionY(), lockDelay.isOn());
    }

    private void rotatePiece(final RotationFlag rotate) {
        if (gameBoard.tryRotatePiece(rotate)) {
            checkLockDelay();
            render();
        }
    }

    private void softDrop() {
        if (gameBoard.trySoftDrop()) {
            scoreMessenger.notifyObservers(new SoftDropEvent(1));
            checkLockDelay();
            render();
        }
    }

    private void hardDrop() {
        final var distance = gameBoard.tryHardDrop();
        if (distance > 0) {
            scoreMessenger.notifyObservers(new HardDropEvent(distance));
            render();
        }
        lockClearAndScorePiece();

    }

    private void holdOrSwap() {
        if (isHoldLock) return;

        TetrominoType currentType = getCurrentTetromino().getType();
        TetrominoType previousHold = holdTetromino;
        setHoldAndNotify(currentType);
        isHoldLock = true;

        boolean spawnSuccessful = (previousHold == null) ? trySpawnNewTetromino() : trySpawnMino(previousHold);

        if (!spawnSuccessful) {
            setGameOver();
        }
        render();
    }

    private void toggleGhostPiece() {
        ghostType = ghostType.next();
        render();
    }

    // =========================================================================
    // PRIVATE METHODS: 3. PIECE LIFECYCLE & SOLIDIFICATION
    // =========================================================================

    private boolean trySpawnNewTetromino() {
        final var piece = generator.getNextPiece();
        if (trySpawnMino(piece)) {
            spawnObservable.notify(generator.peekNext());
            return true;
        }
        return false;
    }

    private boolean trySpawnMino(TetrominoType minoType) {
        if (gameBoard.trySetNewTetromino(minoType)) {
            lockDelay.setFor(getCurrentTetromino());
            return true;
        }
        return false;
    }


    private boolean spawnMinoOrGameOver() {
        if (trySpawnNewTetromino()) return true;
        setGameOver();
        return false;
    }

    private void setHoldAndNotify(TetrominoType currentType) {
        holdTetromino = currentType;
        holdObservable.notify(holdTetromino);
    }

    private void lockClearAndScorePiece() {
        isHoldLock = false;
        gameBoard.lockTetrominoInPlace();
        gameBoard.clearLines();
        final var lastAction = gameBoard.getLastAction();
        final LockPieceEvent lockEvent = createLockEvent(lastAction.tSpin(), lastAction.linesCleared());
        scoreMessenger.notifyObservers(lockEvent);
        spawnMinoOrGameOver();
        gravityAccumulator = 0;
        isDirty.set(true);
    }

    private LockPieceEvent createLockEvent(final TSpin tSpin, int clearedLines) {
        return new LockPieceEvent(clearedLines, tSpin);
    }

    // =========================================================================
    // PRIVATE METHODS: 4. GAME LOOP & GRAVITY PHYSICS & LOCK DELAY
    // =========================================================================

    private void onTickUpdate(Long elapsedTime) {
        if (stateManager.getState() == NEW_GAME || stateManager.getState() == PLAYING) {
            gravityUpdate(elapsedTime);
            lockDelayUpdate(elapsedTime);
            render();
        }
    }

    private void lockDelayUpdate(Long elapsedTime) {
        if (lockDelay.isOn() && lockDelay.onTick(elapsedTime)) {
            lockClearAndScorePiece();
        }
    }

    private void gravityUpdate(Long elapsedTime) {
        gravityAccumulator += elapsedTime;
        while (gravityAccumulator >= movementSpeed) {
            if (gameBoard.tryGravityMove()) {
                gravityAccumulator -= movementSpeed;
                lockDelay.resetLockMode();
            } else {
                gravityAccumulator = 0;
                lockDelay.setLockModeOn();
                lockDelay.onGrounded(getCurrentTetromino().getPositionY());
                break;
            }
        }
    }


    private void resetAccumulator() {
        gravityAccumulator = 0;
    }

    // =========================================================================
    // PRIVATE METHODS: 5. GAME LIFECYCLE & STATE CONTROL
    // =========================================================================

    private void startGame() {
        GameState state = stateManager.getState();
        if (state == PREPARED || state == GAME_OVER) {
            newGame();
            gameLoop.start();
        }
    }

    private void newGame() {
        setHoldAndNotify(null);
        isHoldLock = false;
        stateManager.setState(NEW_GAME);
        isDirty.set(true);
        gameBoard = new GameBoard();
        spawnObservable.notify(generator.peekNext());
        if (spawnMinoOrGameOver()) {
            render();
            resetAccumulator();
            stateManager.setState(PLAYING);
        }
    }

    private void pauseGame() {
        stateManager.setState(PAUSED);
    }

    private void resumeGame() {
        resetAccumulator();
        gameLoop.resume();
        stateManager.setState(PLAYING);
    }

    private void setGameOver() {
        gameLoop.stop();
        stateManager.setState(GAME_OVER);
    }

    private void exitGame() {
        gameExit.run();
    }

    // =========================================================================
    // PRIVATE METHODS: 6. RENDERING & SNAPSHOT HELPERS
    // =========================================================================

    private void render() {
        tetrisCanvas.render(createGameSnapshot());
    }

    private GameSnapshot createGameSnapshot() {
        final var wasDirty = isDirty.getAndSet(false);
        Tetromino current = getCurrentTetromino();
        return new GameSnapshot(getBoardView(), Optional.ofNullable(current), wasDirty, current == null ? 0 : gameBoard.calculateDropDistance(), current == null ? GhostType.NONE : ghostType);
    }

    private BoardView getBoardView() {
        return gameBoard.getBoardView();
    }

    private Tetromino getCurrentTetromino() {
        return gameBoard.getCurrentTetromino();
    }
}