// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codemodernizer

import com.intellij.openapi.project.Project
import software.aws.toolkits.jetbrains.services.amazonq.apps.AmazonQAppFactory

class AppFactory : AmazonQAppFactory {
    override fun createApp(project: Project) = App()
}
