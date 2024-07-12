// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer.model

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.exists
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.jetbrains.services.codemodernizer.utils.unzipFile
import java.io.File
import java.nio.file.Path
import kotlin.io.path.pathString

const val INSTRUCTION_PATH_IN_ZIP = "instructions.json"

/**
 * Represents a CodeModernizer artifact. Essentially a wrapper around the manifest file in the downloaded artifact zip.
 */
open class CodeTransformClientBuildDownloadArtifact(
    val instructions: CodeTransformClientBuildInstructions,
    val outputDirPath: Path
) {
    companion object {
        private val LOG = getLogger<CodeTransformHilDownloadArtifact>()
        private val MAPPER = jacksonObjectMapper()

        /**
         * Extracts the file at [zipPath] and uses its contents to produce a [CodeTransformHilDownloadArtifact].
         * If anything goes wrong during this process an exception is thrown.
         */
        fun create(zipPath: Path, outputDirPath: Path): CodeTransformClientBuildDownloadArtifact {
            if (zipPath.exists()) {
                if (!unzipFile(zipPath, outputDirPath)) {
                    LOG.error { "Could not unzip artifact" }
                    throw RuntimeException("Could not unzip artifact")
                }
                val instructions = extractInstructions(outputDirPath)
                return CodeTransformClientBuildDownloadArtifact(instructions, outputDirPath)
            }
            throw RuntimeException("Could not find artifact")
        }

        /**
         * Attempts to extract the manifest from the zip file. Throws an exception if the manifest is not found or cannot be serialized.
         */
        private fun extractInstructions(dirPath: Path): CodeTransformClientBuildInstructions {
            try {
                val instructionsPath = dirPath.resolve(INSTRUCTION_PATH_IN_ZIP)
                if (!instructionsPath.exists()) {
                    throw RuntimeException("Could not find instructions.json")
                }

                val instructionsFile = File(instructionsPath.pathString)
                val instructions = MAPPER.readValue<CodeTransformClientBuildInstructions>(instructionsFile)
                if (
                    instructions.accountId == null ||
                    instructions.jobId == null ||
                    instructions.buildCommand == null
                // instructions.javaVersion == null   // This value is currently always null. Waiting for backend fix.
                ) {
                    throw RuntimeException(
                        "Instructions contain one or more null values"
                    )
                }
                return instructions
            } catch (exception: JsonProcessingException) {
                throw RuntimeException("Unable to deserialize the instructions JSON")
            }
        }
    }
}
