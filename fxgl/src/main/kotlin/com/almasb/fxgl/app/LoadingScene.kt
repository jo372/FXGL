/*
 * FXGL - JavaFX Game Library. The MIT License (MIT).
 * Copyright (c) AlmasB (almaslvl@gmail.com).
 * See LICENSE for details.
 */

package com.almasb.fxgl.app

import com.almasb.fxgl.dsl.FXGL
import com.almasb.sslogger.Logger
import javafx.concurrent.Task
import javafx.scene.control.ProgressBar
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.Text

/**
 * Loading scene to be used during loading tasks.
 *
 * @author Almas Baimagambetov (AlmasB) (almaslvl@gmail.com)
 */
open class LoadingScene : FXGLScene() {

    private val progress = ProgressBar()
    protected val text = Text()

    private var loadingFinished = false

    init {
        with(progress) {
            setPrefSize(appWidth - 200.0, 10.0)
            translateX = 100.0
            translateY = appHeight - 100.0
        }

        with(text) {
            font = FXGL.getUIFactoryService().newFont(24.0)
            fill = Color.WHITE
        }

        FXGL.centerTextBind(
                text,
                appWidth / 2.0,
                appHeight * 4 / 5.0
        )

        contentRoot.children.addAll(
                Rectangle(appWidth.toDouble(), appHeight.toDouble(), Color.rgb(0, 0, 10)),
                progress,
                text
        )
    }

    /**
     * Bind to progress and text messages of given background loading task.
     *
     * @param task the loading task
     */
    open fun bind(task: Task<*>) {
        progress.progressProperty().bind(task.progressProperty())
        text.textProperty().bind(task.messageProperty())
    }

    override fun onCreate() {
        val initTask = InitAppTask(FXGL.getApp())
        initTask.setOnSucceeded {
            loadingFinished = true
        }

        bind(initTask)

        FXGL.getExecutor().execute(initTask)
    }

    override fun onUpdate(tpf: Double) {
        if (loadingFinished) {
            controller.gotoPlay()
            loadingFinished = false
        }
    }

    /**
     * Clears previous game.
     * Initializes game, physics and UI.
     * This task is rerun every time the game application is restarted.
     */
    private class InitAppTask(private val app: GameApplication) : Task<Void>() {

        companion object {
            private val log = Logger.get<InitAppTask>()
        }

        override fun call(): Void? {
            val start = System.nanoTime()

            clearPreviousGame()

            initGame()
            initPhysics()
            initUI()
            initComplete()

            log.infof("Game initialization took: %.3f sec", (System.nanoTime() - start) / 1000000000.0)

            return null
        }

        private fun clearPreviousGame() {
            log.debug("Clearing previous game")
            FXGL.getGameWorld().clear()
            FXGL.getPhysicsWorld().clear()
            FXGL.getPhysicsWorld().clearCollisionHandlers()
            FXGL.getGameScene().clear()
            FXGL.getWorldProperties().clear()
            FXGL.getGameTimer().clear()
        }

        private fun initGame() {
            update("Initializing Game", 0)

            val vars = hashMapOf<String, Any>()
            app.initGameVars(vars)

            vars.forEach { (name, value) ->
                FXGL.getWorldProperties().setValue(name, value)
            }

            app.initGame()
        }

        private fun initPhysics() {
            update("Initializing Physics", 1)
            app.initPhysics()
        }

        private fun initUI() {
            update("Initializing UI", 2)
            app.initUI()
        }

        private fun initComplete() {
            update("Initialization Complete", 3)
            FXGL.getGameController().onGameReady(FXGL.getWorldProperties())
        }

        private fun update(message: String, step: Int) {
            log.debug(message)
            updateMessage(message)
            updateProgress(step.toLong(), 3)
        }

        override fun failed() {
            Thread.getDefaultUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), exception ?: RuntimeException("Initialization failed"))
        }
    }
}