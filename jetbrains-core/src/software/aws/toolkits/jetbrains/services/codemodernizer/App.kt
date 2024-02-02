// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer

import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import software.aws.toolkits.jetbrains.core.coroutines.disposableCoroutineScope
import software.aws.toolkits.jetbrains.services.amazonq.apps.AmazonQApp
import software.aws.toolkits.jetbrains.services.amazonq.apps.AmazonQAppInitContext
import software.aws.toolkits.jetbrains.services.amazonq.messages.AmazonQMessage
import software.aws.toolkits.jetbrains.services.codemodernizer.controller.CodeModernizerChatController
import software.aws.toolkits.jetbrains.services.cwc.commands.ActionRegistrar
import software.aws.toolkits.jetbrains.services.cwc.messages.IncomingCwcMessage

class App : AmazonQApp {

    private val scope = disposableCoroutineScope(this)

    override val tabTypes = listOf("cwc")

    override fun init(context: AmazonQAppInitContext) {
        // Create CWC chat controller
        val chatController = CodeModernizerChatController(context)

        context.messageTypeRegistry.register(
            // JB specific (not in vscode)
            "transform" to IncomingCwcMessage.Transform::class,
        )

        scope.launch {
            merge(ActionRegistrar.instance.flow, context.messagesFromUiToApp.flow).collect { message ->
                // Launch a new coroutine to handle each message
                scope.launch { handleMessage(message, chatController) }
            }
        }
    }

    private suspend fun handleMessage(message: AmazonQMessage, chatController: CodeModernizerChatController) {
        when (message) {
            is IncomingCwcMessage.Transform -> chatController.processTransformQuickAction(message)
        }
    }

    override fun dispose() {
        // nothing to do
    }
}
