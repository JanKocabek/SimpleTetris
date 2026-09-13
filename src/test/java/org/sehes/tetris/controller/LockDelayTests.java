package org.sehes.tetris.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sehes.tetris.config.GameParameters;
import org.sehes.tetris.model.TetrominoFactory;
import org.sehes.tetris.model.TetrominoType;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;


public class LockDelayTests {
    LockDelay lockDelay;

    @BeforeEach
    public void setUp() {
        //Arrange
        lockDelay = new LockDelay();
        lockDelay.setFor(TetrominoFactory.spawnTetromino(TetrominoType.T, GameParameters.SPAWN_POINT));
    }

    @Test
    void testLockDoesntHappenedBefore500() {
        //Act
        final var result = lockDelay.onTick(499);
        //Assert
        assertThat(result).isFalse();
    }

    @Test
    void testLockTrueWIth500() {
        //Act
        final var time = TimeUnit.MILLISECONDS.toNanos(500);
        final var result = lockDelay.onTick(time);
        //Assert
        assertThat(result).isTrue();
    }

    @Test
    void testLockTrueWIthMore500() {
        //Act
        final var time = TimeUnit.MILLISECONDS.toNanos(600);
        final var result = lockDelay.onTick(time);
        //Assert
        assertThat(result).isTrue();
    }

    @Test
    void testIsLockModeIsNotChangeOnTickItself() {
        //Act
        final var expected = false;
        final var time= TimeUnit.MILLISECONDS.toNanos(500);
        lockDelay.onTick(time);
        final var result = lockDelay.isOn();
        assertThat(result).isEqualTo(expected);
    }

    @Test
void testGravityFallNotAddIntoLockMoveCounter(){
        lockDelay.setFor(TetrominoFactory.spawnTetromino(TetrominoType.T, GameParameters.SPAWN_POINT));
        lockDelay.onGrounded(20);
        lockDelay.onGrounded(20);
        assertThat(lockDelay).extracting("lockMoves").isEqualTo(0);
    }
}
