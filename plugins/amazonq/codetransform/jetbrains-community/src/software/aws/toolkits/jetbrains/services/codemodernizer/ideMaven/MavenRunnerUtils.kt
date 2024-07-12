// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.ideMaven

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
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

fun runMavenClientBuild(
    sourceFolder: File, logger: Logger, project: Project, command: String, javaVersion: String
): MavenCopyCommandsResult {
    val telemetry = CodeTransformTelemetryManager.getInstance(project)

    val currentTimestamp = System.currentTimeMillis()
    val tmpDestinationDir = Files.createTempDirectory("transformation_client_build_$currentTimestamp")

    logger.info { "Created temp directory $tmpDestinationDir for local build" }

    val runnable = runMavenClientBuildCommand(sourceFolder, tmpDestinationDir, logger, project, command, javaVersion, telemetry)
    runnable.await()
    if (runnable.isComplete()) {
        logger.info { "IntelliJ bundled Maven executed successfully" }
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
    // Currently backend provide full maven commands like "mvn clean verify", but maven runner commands need to be separated.
    // Remove Maven keyword, then split into multiple commands by whitespaces.
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

            // Set the JRE version for the MavenRunnerSettings
            val jdk: Pair<String, String>? = getJdkName(getJavaVersion(javaVersion))
            if (jdk != null) {
                mvnSettings.setJreName(jdk.first)
                logger.info { "Running client side build with JRE: ${jdk.second}" }
            } else {
                // DEMO: TODO: create specific exception type to trigger chat message
                throw Exception("Unable to find Java 8 JRE")
            }

            transformMvnRunner.run(commandParams, mvnSettings, transformRunnable)
        } catch (t: Throwable) {
            val error = "Maven: Unexpected error when executing Maven in client side build"
            transformRunnable.setExitCode(Integer.MIN_VALUE) // to stop looking for the exitCode
            logger.info(t) { error }
            telemetry.mvnBuildFailed(CodeTransformMavenBuildCommand.IDEBundledMaven, error)
        }
    }
    return transformRunnable
}

// Example jdk name and versionString:
//      1.8 -> java version "1.8.0_412"
//      corretto-1.8 -> java version "1.8.0_412"
//      corretto-17 -> Amazon Corretto 17.0.11 - aarch64
private fun getJdkName(javaVersion: String): Pair<String, String>? {
    val jdkTable = ProjectJdkTable.getInstance()
    for(jdk in jdkTable.allJdks) {
        val jdkName = jdk.name
        val jdkVersion = jdk.versionString.orEmpty()
        if (jdkName.isNotEmpty() && jdkName.contains(javaVersion)) {
            return jdkName to jdkVersion
        }
    }
    return null
}

private fun getJavaVersion(javaVersion: String): String {
    return when (javaVersion) {
        "JAVA_8" -> "1.8"
        "JAVA_11" -> "11"
        "JAVA_17" -> "17"
        else -> throw IllegalArgumentException("Invalid Java version: $javaVersion")
    }
}
