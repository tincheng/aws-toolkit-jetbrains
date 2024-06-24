// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.patch.ApplyPatchDefaultExecutor
import com.intellij.openapi.vcs.changes.patch.ApplyPatchDifferentiatedDialog
import com.intellij.openapi.vcs.changes.patch.ApplyPatchMode
import com.intellij.openapi.vcs.changes.patch.ImportToShelfExecutor
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.execution.MavenRunnerSettings
import org.slf4j.Logger
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.info
import software.aws.toolkits.jetbrains.services.codemodernizer.CodeTransformTelemetryManager
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MavenCopyCommandsResult
import software.aws.toolkits.jetbrains.services.codemodernizer.model.MavenDependencyReportCommandsResult
import software.aws.toolkits.telemetry.CodeTransformMavenBuildCommand
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private fun emitMavenFailure(error: String, logger: Logger, telemetry: CodeTransformTelemetryManager, throwable: Throwable? = null) {
    if (throwable != null) logger.error(throwable) { error } else logger.error { error }
    telemetry.mvnBuildFailed(CodeTransformMavenBuildCommand.IDEBundledMaven, error)
}

fun runHilMavenCopyDependency(
    sourceFolder: File,
    destinationDir: File,
    buildlogBuilder: StringBuilder,
    logger: Logger,
    project: Project
): MavenCopyCommandsResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    logger.info { "Executing IntelliJ bundled Maven" }
    try {
        // Create shared parameters
        val transformMvnRunner = TransformMavenRunner(project)
        val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

        // run copy dependencies
        val copyDependenciesRunnable =
            runMavenCopyDependencies(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, destinationDir.toPath(), logger, telemetry)
        copyDependenciesRunnable.await()
        buildlogBuilder.appendLine(copyDependenciesRunnable.getOutput())
        if (copyDependenciesRunnable.isComplete()) {
            val successMsg = "IntelliJ IDEA bundled Maven copy-dependencies executed successfully"
            logger.info { successMsg }
            buildlogBuilder.appendLine(successMsg)
        } else if (copyDependenciesRunnable.isTerminated()) {
            return MavenCopyCommandsResult.Cancelled
        } else {
            emitMavenFailure("Maven Copy: bundled Maven failed: exitCode ${copyDependenciesRunnable.isComplete()}", logger, telemetry)
        }
    } catch (t: Throwable) {
        emitMavenFailure("IntelliJ bundled Maven executed failed: ${t.message}", logger, telemetry, t)
        return MavenCopyCommandsResult.Failure
    }
    // When all commands executed successfully, show the transformation hub
    return MavenCopyCommandsResult.Success(destinationDir)
}

fun runMavenCopyCommands(sourceFolder: File, buildlogBuilder: StringBuilder, logger: Logger, project: Project): MavenCopyCommandsResult {
    val currentTimestamp = System.currentTimeMillis()
    val destinationDir = Files.createTempDirectory("transformation_dependencies_temp_$currentTimestamp")
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    logger.info { "Executing IntelliJ bundled Maven" }
    try {
        // Create shared parameters
        val transformMvnRunner = TransformMavenRunner(project)
        val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

        // run copy dependencies
        val copyDependenciesRunnable =
            runMavenCopyDependencies(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, destinationDir, logger, telemetry)
        copyDependenciesRunnable.await()
        buildlogBuilder.appendLine(copyDependenciesRunnable.getOutput())
        if (copyDependenciesRunnable.isComplete()) {
            val successMsg = "IntelliJ IDEA bundled Maven copy-dependencies executed successfully"
            logger.info { successMsg }
            buildlogBuilder.appendLine(successMsg)
        } else if (copyDependenciesRunnable.isTerminated()) {
            return MavenCopyCommandsResult.Cancelled
        } else {
            emitMavenFailure("Maven Copy: bundled Maven failed: exitCode ${copyDependenciesRunnable.isComplete()}", logger, telemetry)
        }

        // Run clean
        val cleanRunnable = runMavenClean(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, logger, telemetry, destinationDir)
        cleanRunnable.await()
        buildlogBuilder.appendLine(cleanRunnable.getOutput())
        if (cleanRunnable.isComplete()) {
            val successMsg = "IntelliJ bundled Maven clean executed successfully"
            logger.info { successMsg }
            buildlogBuilder.appendLine(successMsg)
        } else if (cleanRunnable.isTerminated()) {
            return MavenCopyCommandsResult.Cancelled
        } else {
            emitMavenFailure("Maven Clean: bundled Maven failed: exitCode ${cleanRunnable.isComplete()}", logger, telemetry)
            return MavenCopyCommandsResult.Failure
        }

        // Run install
        val installRunnable = runMavenInstall(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, logger, telemetry, destinationDir)
        installRunnable.await()
        buildlogBuilder.appendLine(installRunnable.getOutput())
        if (installRunnable.isComplete()) {
            val successMsg = "IntelliJ bundled Maven install executed successfully"
            logger.info { successMsg }
            buildlogBuilder.appendLine(successMsg)
        } else if (installRunnable.isTerminated()) {
            return MavenCopyCommandsResult.Cancelled
        } else {
            emitMavenFailure("Maven Install: bundled Maven failed: exitCode ${installRunnable.isComplete()}", logger, telemetry)
            return MavenCopyCommandsResult.Failure
        }
    } catch (t: Throwable) {
        emitMavenFailure("IntelliJ bundled Maven executed failed: ${t.message}", logger, telemetry, t)
        return MavenCopyCommandsResult.Failure
    }
    // When all commands executed successfully, show the transformation hub
    return MavenCopyCommandsResult.Success(destinationDir.toFile())
}

fun runMavenOpenRewrite(sourceFolder: File, silent: Boolean = true, buildlogBuilder: StringBuilder, logger: Logger, project: Project): MavenCopyCommandsResult {
    val currentTimestamp = System.currentTimeMillis()
    val destinationDir = Files.createDirectories(Paths.get("/Users/dangmaul/Desktop/dangmaulClientBuildOutputDir"))
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    logger.info { "Executing IntelliJ bundled Maven" }
    try {
        // Create shared parameters
        val transformMvnRunner = TransformMavenRunner(project)
        val mvnSettings = MavenRunner.getInstance(project).settings.clone()
                    // run copy dependencies
        val openRewriteRunnable =
            runMavenOpenRewrite(sourceFolder, silent, buildlogBuilder, mvnSettings, transformMvnRunner, logger, telemetry)
        openRewriteRunnable.await()
        buildlogBuilder.appendLine(openRewriteRunnable.getOutput())
        if (openRewriteRunnable.isComplete()) {
            val successMsg = "IntelliJ IDE bundled Maven OpenRewrite successfully"
            logger.info { successMsg }
            println(
                Files.readString(sourceFolder.toPath().resolve("target/rewrite/rewrite.patch"))
            )
            logger.info { "PATH = ${sourceFolder.toPath().resolve("target/rewrite/rewrite.patch")}"}
            runInEdt {
                val dialog = ApplyPatchDifferentiatedDialog(
                    project,
                    ApplyPatchDefaultExecutor(project),
                    listOf(ImportToShelfExecutor(project)),
                    ApplyPatchMode.APPLY,
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(sourceFolder.toPath().resolve("target/rewrite/rewrite.patch").toFile()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                )
                dialog.isModal = true
                dialog.showAndGet()
            }
            buildlogBuilder.appendLine(successMsg)
        } else if (openRewriteRunnable.isTerminated()) {
            return MavenCopyCommandsResult.Cancelled
        } else {
            emitMavenFailure("Maven Build: bundled Maven OpenRewrite failed: exitCode ${openRewriteRunnable.isComplete()}", logger, telemetry)
        }
    } catch (t: Throwable) {
        emitMavenFailure("IntelliJ bundled Maven OpenRewrite failed: ${t.message}", logger, telemetry, t)
        return MavenCopyCommandsResult.Failure
    }
    // When all commands executed successfully, show the transformation hub
    return MavenCopyCommandsResult.Success(destinationDir.toFile())
}

private fun runMavenOpenRewrite(
    sourceFolder: File,
    silent: Boolean = true,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger,
    telemetry: CodeTransformTelemetryManager,
): TransformRunnable {
    buildlogBuilder.appendLine("OR Start")
    logger.info { "Executing OpenRewrite Locally in sourceDirectory = $sourceFolder. ${if(silent) "Running with --log-file mvn flag." else "false"}" }
    val commandList = mutableListOf("-U",
        "org.openrewrite.maven:rewrite-maven-plugin:5.28.0:dryRun",
        "-Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-apache:0.1.2,org.openrewrite.recipe:rewrite-struts:0.1.1,org.openrewrite.recipe:rewrite-launchdarkly:0.2.1,org.openrewrite.recipe:rewrite-jenkins:0.3.6,org.openrewrite.recipe:rewrite-hibernate:1.2.2,org.openrewrite.recipe:rewrite-liberty:1.1.6,org.openrewrite.recipe:rewrite-logging-frameworks:2.5.0,org.openrewrite.recipe:rewrite-migrate-java:2.11.0,org.openrewrite.recipe:rewrite-micronaut:2.3.1,org.openrewrite.recipe:rewrite-spring:5.7.0,org.openrewrite.recipe:rewrite-testing-frameworks:2.6.0,org.openrewrite.recipe:rewrite-openapi:0.0.4,org.openrewrite.recipe:rewrite-quarkus:2.3.0,org.openrewrite.recipe:rewrite-okhttp:0.1.6",
        "-Drewrite.activeRecipes=com.amazonaws.elasticgumby.java.migrate.PrepareUpgradeToJava17")
    if (silent) {
        commandList.add("--log-file")
        commandList.add("client_or_run.log")
    }
    val buildCommandParams = MavenRunnerParameters(
        false,
        sourceFolder.toString(),
        null,
        commandList,
        emptyList<String>(),
        null
    )
    val copyTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(buildCommandParams, mvnSettings, copyTransformRunnable)
        } catch (t: Throwable) {
            val error = "OpenRewrite Error: Unexpected error when executing OpenRewrite Locally in sourceDirectory = $sourceFolder."
            copyTransformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            logger.info(t) { error }
            buildlogBuilder.appendLine("OR failed: ${t.message}")
            telemetry.mvnBuildFailed(CodeTransformMavenBuildCommand.IDEBundledMaven, error)
        }
    }
    return copyTransformRunnable
}

private fun runMavenCopyDependencies(
    sourceFolder: File,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    destinationDir: Path,
    logger: Logger,
    telemetry: CodeTransformTelemetryManager,
): TransformRunnable {
    buildlogBuilder.appendLine("Command Run: IntelliJ IDEA bundled Maven dependency:copy-dependencies")
    val copyCommandList = listOf(
        "dependency:copy-dependencies",
        "-DoutputDirectory=$destinationDir",
        "-Dmdep.useRepositoryLayout=true",
        "-Dmdep.copyPom=true",
        "-Dmdep.addParentPoms=true",
    )
    val copyParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        copyCommandList,
        emptyList<String>(),
        null
    )
    val copyTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(copyParams, mvnSettings, copyTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Copy: Unexpected error when executing bundled Maven copy dependencies"
            copyTransformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            logger.info(t) { error }
            buildlogBuilder.appendLine("IntelliJ bundled Maven copy dependencies failed: ${t.message}")
            telemetry.mvnBuildFailed(CodeTransformMavenBuildCommand.IDEBundledMaven, error)
        }
    }
    return copyTransformRunnable
}

private fun runMavenClean(
    sourceFolder: File,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger,
    telemetry: CodeTransformTelemetryManager,
    destinationDir: Path
): TransformRunnable {
    buildlogBuilder.appendLine("Command Run: IntelliJ IDEA bundled Maven clean")
    val cleanParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        listOf("-Dmaven.repo.local=$destinationDir", "clean"),
        emptyList<String>(),
        null
    )
    val cleanTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(cleanParams, mvnSettings, cleanTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Clean: Unexpected error when executing bundled Maven clean"
            cleanTransformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildlogBuilder.appendLine("IntelliJ bundled Maven clean failed: ${t.message}")
            emitMavenFailure(error, logger, telemetry, t)
        }
    }
    return cleanTransformRunnable
}

private fun runMavenInstall(
    sourceFolder: File,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger,
    telemetry: CodeTransformTelemetryManager,
    destinationDir: Path
): TransformRunnable {
    buildlogBuilder.appendLine("Command Run: IntelliJ IDEA bundled Maven install")
    val installParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        listOf("-Dmaven.repo.local=$destinationDir", "install"),
        emptyList<String>(),
        null
    )
    val installTransformRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(installParams, mvnSettings, installTransformRunnable)
        } catch (t: Throwable) {
            val error = "Maven Install: Unexpected error when executing bundled Maven install"
            installTransformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildlogBuilder.appendLine("IntelliJ bundled Maven install failed: ${t.message}")
            emitMavenFailure(error, logger, telemetry, t)
        }
    }
    return installTransformRunnable
}

fun runMavenClientBuild(
    sourceFolder: File, logger: Logger, project: Project, command: String, javaVersion: String
): MavenCopyCommandsResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)

    val currentTimestamp = System.currentTimeMillis()
    val tmpDestinationDir = Files.createTempDirectory("transformation_client_build_$currentTimestamp")

    logger.info { "DEMO: created temp directory $tmpDestinationDir for local build" }

    val runnable = runMavenClientBuildCommand(sourceFolder, tmpDestinationDir, logger, project, command, javaVersion, telemetry)
    runnable.await()
    if (runnable.isComplete()) {
        logger.info { "DEMO: IntelliJ bundled Maven executed successfully" }
    } else if (runnable.isTerminated()) {
        return MavenCopyCommandsResult.Cancelled
    } else {
        emitMavenFailure("Bundled Maven failed: exitCode ${runnable.isComplete()}", logger, telemetry)
        return MavenCopyCommandsResult.Failure
    }
    // When all commands executed successfully, show the transformation hub
    return MavenCopyCommandsResult.Success(tmpDestinationDir.toFile())
}

private fun runMavenClientBuildCommand(
    sourceFolder: File,
    tmpDestinationDir: Path,
    logger: Logger,
    project: Project,
    command: String,
    javaVersion: String,
    telemetry: CodeTransformTelemetryManager,
): TransformRunnable {
    // Remove Maven keyword, then split into multiple commands by whitespaces.
    // Currently, command received has one extra space after mvn.
    val splitCommands = command.replace("mvn ", "").split(" ")
    val finalCommands = mutableListOf("-Dmaven.repo.local=$tmpDestinationDir -Dmaven.main.skip=true -Dmaven.test.skip=true")
    finalCommands += splitCommands

    val commandParams = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        finalCommands,
        emptyList<String>(),
        null
    )
    val transformRunnable = TransformRunnable()
    runInEdt {
        try {
            val transformMvnRunner = TransformMavenRunner(project)
            val mvnSettings = MavenRunner.getInstance(project).settings.clone()

            // TODO (Post-demo): catch error if JRE version cannot be found, and ask customer (via chat) to add the JDK in project module.
            //  The JRE names here must match available JREs in the Build Tools -> Maven -> Runner -> JRE settings
            // Set the JRE version for the MavenRunnerSettings
            if (javaVersion == "JAVA_8") {
                //mvnSettings.setJreName("/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home/")
                mvnSettings.setJreName("corretto-1.8")
            } else if (javaVersion == "JAVA_17") {
                //mvnSettings.setJreName("/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home/")
                mvnSettings.setJreName("corretto-17")
            }

            transformMvnRunner.run(commandParams, mvnSettings, transformRunnable)
        } catch (t: Throwable) {
            transformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            // TODO: telemetry
        }
    }
    return transformRunnable
}

private fun runMavenDependencyUpdatesReport(
    sourceFolder: File,
    buildlogBuilder: StringBuilder,
    mvnSettings: MavenRunnerSettings,
    transformMavenRunner: TransformMavenRunner,
    logger: Logger,
    telemetry: CodeTransformTelemetryManager,
): TransformRunnable {
    buildlogBuilder.appendLine("Command Run: IntelliJ IDEA bundled Maven dependency updates report")

    val dependencyUpdatesReportCommandList = listOf(
        "versions:dependency-updates-aggregate-report",
        "-DonlyProjectDependencies=true",
        "-DdependencyUpdatesReportFormats=xml",
    )

    val params = MavenRunnerParameters(
        false,
        sourceFolder.absolutePath,
        null,
        dependencyUpdatesReportCommandList,
        emptyList<String>(),
        null
    )
    val dependencyUpdatesReportRunnable = TransformRunnable()
    runInEdt {
        try {
            transformMavenRunner.run(params, mvnSettings, dependencyUpdatesReportRunnable)
        } catch (t: Throwable) {
            val error = "Maven dependency report: Unexpected error when executing bundled Maven dependency updates report"
            dependencyUpdatesReportRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            buildlogBuilder.appendLine("IntelliJ bundled Maven dependency updates report failed: ${t.message}")
            emitMavenFailure(error, logger, telemetry, t)
        }
    }
    return dependencyUpdatesReportRunnable
}

fun runDependencyReportCommands(sourceFolder: File, buildlogBuilder: StringBuilder, logger: Logger, project: Project): MavenDependencyReportCommandsResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)
    logger.info { "Executing IntelliJ bundled Maven" }

    val transformMvnRunner = TransformMavenRunner(project)
    val mvnSettings = MavenRunner.getInstance(project).settings.clone() // clone required to avoid editing user settings

    val runnable = runMavenDependencyUpdatesReport(sourceFolder, buildlogBuilder, mvnSettings, transformMvnRunner, logger, telemetry)
    runnable.await()
    buildlogBuilder.appendLine(runnable.getOutput())
    if (runnable.isComplete()) {
        val successMsg = "IntelliJ bundled Maven dependency report executed successfully"
        logger.info { successMsg }
        buildlogBuilder.appendLine(successMsg)
    } else if (runnable.isTerminated()) {
        return MavenDependencyReportCommandsResult.Cancelled
    } else {
        emitMavenFailure("Maven dependency report: bundled Maven failed: exitCode ${runnable.isComplete()}", logger, telemetry)
        return MavenDependencyReportCommandsResult.Failure
    }

    return MavenDependencyReportCommandsResult.Success
}
