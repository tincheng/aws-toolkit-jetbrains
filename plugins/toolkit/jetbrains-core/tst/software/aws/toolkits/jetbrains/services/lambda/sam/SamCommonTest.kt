// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.sam

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import software.aws.toolkits.core.utils.exists
import software.aws.toolkits.jetbrains.core.executables.ExecutableManager
import software.aws.toolkits.jetbrains.services.lambda.sam.SamCommonTestUtils.makeATestSam
import software.aws.toolkits.jetbrains.utils.rules.HeavyJavaCodeInsightTestFixtureRule
import software.aws.toolkits.jetbrains.utils.satisfiesKt
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

class SamCommonTest {
    @Rule
    @JvmField
    val projectRule = HeavyJavaCodeInsightTestFixtureRule()

    private val executableManager = ExecutableManager.getInstance()

    @Test
    fun getVersion_badPath() {
        executableManager.removeExecutable(SamExecutable())
        executableManager.setExecutablePath(SamExecutable(), Paths.get("/bad/path/that/will/not/work")).toCompletableFuture()
            .get(1, TimeUnit.SECONDS)
        val actualVersion = SamCommon.getVersionString()
        assertThat(actualVersion).isEqualTo("UNKNOWN")
    }

    @Test
    fun getVersion_Valid_exitNonZero() {
        val samPath = makeATestSam("stderr", exitCode = 100)
        executableManager.removeExecutable(SamExecutable())
        executableManager.setExecutablePath(SamExecutable(), samPath).toCompletableFuture().get(1, TimeUnit.SECONDS)
        val actualVersion = SamCommon.getVersionString()
        assertThat(actualVersion).isEqualTo("UNKNOWN")
    }

    @Test(expected = java.lang.AssertionError::class)
    fun getTemplateFromDirectory_noYaml() {
        val basePath = projectRule.project.basePath?.let { Paths.get(it) } ?: throw NullPointerException("basepath is null")
        if (!basePath.exists()) {
            Files.createDirectory(basePath)
        }
        val projectBase = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(basePath.toFile())
            ?: throw NullPointerException("project base is null ($basePath)")
        SamCommon.getTemplateFromDirectory(projectBase)
    }

    @Test
    fun getTemplateFromDirectory_singleYaml() {
        val file = yamlTemplate()
        runReadAction {
            val dir = file.containingDirectory.virtualFile
            val templateFile = SamCommon.getTemplateFromDirectory(dir)
            assertThat(templateFile).isNotNull
        }
    }

    @Test(expected = java.lang.AssertionError::class)
    fun getTemplateFromDirectory_multipleYaml() {
        val file = yamlTemplate()
        yamlTemplate(filename = "template.yml")
        yamlTemplate(filename = "theBestTemplate.yml")
        yamlTemplate(filename = "i_need_more_templates.yaml")
        runReadAction {
            val dir = file.containingDirectory.virtualFile
            SamCommon.getTemplateFromDirectory(dir)
        }
    }

    @Test
    fun getCodeUri_noUri() {
        val file = yamlTemplate(
            """
Description: "Some description"
Resources:
    MyFunction:
        Type: AWS::Serverless::Function
        Properties:
            Handler: helloworld.App::handleRequest
            Runtime: java8
            CodeUri: target/out.jar
            """.trimIndent()
        )
        runInEdtAndWait {
            projectRule.fixture.addFileToProject("target/out.jar", "")
        }
        runReadAction {
            val dir = file.containingDirectory.virtualFile
            val templateFile = SamCommon.getTemplateFromDirectory(dir)
            assertNotNull(templateFile)
            val codeUris = SamCommon.getCodeUrisFromTemplate(projectRule.project, templateFile)
            assertThat(codeUris).isEmpty()
        }
    }

    @Test
    fun getCodeUri_singleUri() {
        val file = yamlTemplate(
            """
Description: "Some description"
Resources:
    HelloWorldFunction:
        Type: AWS::Serverless::Function
        Properties:
            CodeUri: hello_world/
            Handler: app.handle_request
            Runtime: java8
            """.trimIndent()
        )
        createChildren("hello_world")
        runInEdtAndWait {
            projectRule.fixture.addFileToProject("target/out.jar", "")
        }
        runReadAction {
            val dir = file.containingDirectory.virtualFile
            val templateFile = SamCommon.getTemplateFromDirectory(dir)
            assertNotNull(templateFile)
            val codeUris = SamCommon.getCodeUrisFromTemplate(projectRule.project, templateFile)
            assertThat(codeUris).singleElement().satisfiesKt {
                assertThat(it.name).isEqualTo("hello_world")
            }
        }
    }

    @Test
    fun getCodeUri_samAndNotSam() {
        val file = yamlTemplate(
            """
Description: "Some description"
Resources:
    HelloWorldFunction:
        Type: AWS::Serverless::Function
        Properties:
            CodeUri: hello_world/
            Handler: app.handle_request
            Runtime: java8
    MyDynamoTable:
        Type: AWS::DynamoDB::Table
        Properties:
            AttributeDefinitions:
                - AttributeName: "ArtistId"
                  AttributeType: "S"
                - AttributeName: "Concert"
                  AttributeType: "S"
            KeySchema:
                - AttributeName: "ArtistId"
                  KeyType: "HASH"
                - AttributeName: "Concert"
                  KeyType: "RANGE"
            ProvisionedThroughput:
                ReadCapacityUnits: 1
                WriteCapacityUnits: 1
            """.trimIndent()
        )
        createChildren("hello_world")
        runReadAction {
            val dir = file.containingDirectory.virtualFile
            val templateFile = SamCommon.getTemplateFromDirectory(dir)
            assertNotNull(templateFile)
            val codeUris = SamCommon.getCodeUrisFromTemplate(projectRule.project, templateFile)
            assertThat(codeUris).singleElement().satisfiesKt {
                assertThat(it.name).isEqualTo("hello_world")
            }
        }
    }

    @Test
    fun getCodeUri_multipleUris() {
        val file = yamlTemplate(
            """
Description: "Some description"
Resources:
    MyFunction:
        Type: AWS::Serverless::Function
        Properties:
            Handler: helloworld.App::handleRequest
            Runtime: java8
            CodeUri: target/out.jar
    HelloWorldFunction:
        Type: AWS::Serverless::Function
        Properties:
            CodeUri: hello_world/
            Handler: app.handle_request
            Runtime: java8
    AnotherHelloWorldFunction:
        Type: AWS::Serverless::Function
        Properties:
            CodeUri: hello_world_42/
            Handler: app.handle_request
            Runtime: java8
            """.trimIndent()
        )
        createChildren("hello_world")
        createChildren("hello_world_42")
        createChildren("target", "out.jar")
        runReadAction {
            val dir = file.containingDirectory.virtualFile
            val templateFile = SamCommon.getTemplateFromDirectory(dir)
            assertNotNull(templateFile)
            val codeUris = SamCommon.getCodeUrisFromTemplate(projectRule.project, templateFile)
            assertThat(codeUris).hasSize(2).satisfiesExactlyInAnyOrder(
                { assertThat(it.name).isEqualTo("hello_world") },
                { assertThat(it.name).isEqualTo("hello_world_42") }
            )
        }
    }

    private fun yamlTemplate(template: String = TEST_TEMPLATE, filename: String = "template.yaml"): PsiFile = runInEdtAndGet {
        projectRule.fixture.addFileToProject(filename, template)
    }

    private fun createChildren(directory: String, file: String = "TestFile") {
        projectRule.fixture.addFileToProject("$directory/$file", "")
    }

    private companion object {
        val TEST_TEMPLATE =
            """
Description: "Some description"
Resources:
    MyFunction:
        Type: AWS::Serverless::Function
        Properties:
            Handler: helloworld.App::handleRequest
            Runtime: java8
            CodeUri: target/out.jar
    MyDynamoTable:
        Type: AWS::DynamoDB::Table
        Properties:
            AttributeDefinitions:
                - AttributeName: "ArtistId"
                  AttributeType: "S"
                - AttributeName: "Concert"
                  AttributeType: "S"
            KeySchema:
                - AttributeName: "ArtistId"
                  KeyType: "HASH"
                - AttributeName: "Concert"
                  KeyType: "RANGE"
            ProvisionedThroughput:
                ReadCapacityUnits: 1
                WriteCapacityUnits: 1
            """.trimIndent()
    }
}
