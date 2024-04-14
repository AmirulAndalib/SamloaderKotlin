package tk.zwander.commonCompose.view.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import io.ktor.utils.io.core.internal.DangerousInternalIoApi
import io.ktor.utils.io.core.toByteArray
import korlibs.crypto.MD5
import kotlinx.coroutines.launch
import my.nanihadesuka.compose.ColumnScrollbarNew
import my.nanihadesuka.compose.ScrollbarSelectionMode
import tk.zwander.common.data.DecryptFileInfo
import tk.zwander.common.data.PlatformFile
import tk.zwander.common.tools.CryptUtils
import tk.zwander.common.util.Event
import tk.zwander.common.util.eventManager
import tk.zwander.common.util.invoke
import tk.zwander.commonCompose.locals.LocalDecryptModel
import tk.zwander.commonCompose.model.DecryptModel
import tk.zwander.commonCompose.util.OffsetCorrectedIdentityTransformation
import tk.zwander.commonCompose.util.ThemeConstants
import tk.zwander.commonCompose.util.collectAsImmediateMutableState
import tk.zwander.commonCompose.view.components.HybridButton
import tk.zwander.commonCompose.view.components.InWindowAlertDialog
import tk.zwander.commonCompose.view.components.MRFLayout
import tk.zwander.commonCompose.view.components.ProgressInfo
import tk.zwander.commonCompose.view.components.SplitComponent
import tk.zwander.samloaderkotlin.resources.MR
import java.io.File
import kotlin.time.ExperimentalTime

@OptIn(DangerousInternalIoApi::class, ExperimentalTime::class)
private suspend fun onDecrypt(model: DecryptModel) {
    eventManager.sendEvent(Event.Decrypt.Start)
    val info = model.fileToDecrypt.value!!
    val inputFile = info.encFile
    val outputFile = info.decFile
    val modelKey = model.decryptionKey.value

    val key = when {
        !modelKey.isNullOrBlank() -> MD5.digest(modelKey.toByteArray()).bytes
        inputFile.getName().endsWith(".enc2") -> {
            CryptUtils.getV2Key(
                model.fw.value ?: "",
                model.model.value ?: "",
                model.region.value ?: "",
            ).first
        }
        else -> {
            try {
                CryptUtils.getV4Key(
                    model.fw.value ?: "",
                    model.model.value ?: "",
                    model.region.value ?: "",
                    model.imeiSerial.value ?: "",
                ).first
            } catch (e: Throwable) {
                println("Unable to retrieve v4 key ${e.message}.")
                model.endJob(MR.strings.decryptError(e.message.toString()))
                return
            }
        }
    }

    val inputStream = try {
        inputFile.openInputStream() ?: return
    } catch (e: Throwable) {
        println("Unable to open input file ${e.message}.")
        model.endJob(MR.strings.decryptError(e.message.toString()))
        return
    }

    val outputStream = try {
        outputFile.openOutputStream() ?: return
    } catch (e: Throwable) {
        println("Unable to open output file ${e.message}.")
        model.endJob(MR.strings.decryptError(e.message.toString()))
        return
    }

    CryptUtils.decryptProgress(inputStream, outputStream, key, inputFile.getLength()) { current, max, bps ->
        model.progress.value = current to max
        model.speed.value = bps
        eventManager.sendEvent(Event.Decrypt.Progress(MR.strings.decrypting(), current, max))
    }

    eventManager.sendEvent(Event.Decrypt.Finish)
    model.endJob(MR.strings.done())
}

private suspend fun onOpenFile(model: DecryptModel) {
    eventManager.sendEvent(Event.Decrypt.GetInput { info ->
        handleFileInput(model, info)
    })
}

private fun handleFileInput(model: DecryptModel, info: DecryptFileInfo?) {
    if (info != null) {
        if (!info.encFile.getName().endsWith(".enc2") &&
            !info.encFile.getName().endsWith(".enc4")) {
            model.endJob(MR.strings.selectEncrypted())
        } else {
            model.endJob("")
            model.fileToDecrypt.value = info
        }
    } else {
        model.endJob("")
    }
}

@Composable
expect fun Modifier.handleFileDrag(
    enabled: Boolean = true,
    onDragStart: (PlatformFile?) -> Unit = {},
    onDrag: (PlatformFile?) -> Unit = {},
    onDragExit: () -> Unit = {},
    onDrop: (PlatformFile?) -> Unit = {},
): Modifier

/**
 * The Decrypter View.
 */
@DangerousInternalIoApi
@ExperimentalTime
@Composable
internal fun DecryptView() {
    val model = LocalDecryptModel.current

    val fw by model.fw.collectAsState()
    val modelModel by model.model.collectAsState()
    val region by model.region.collectAsState()
    val fileToDecrypt by model.fileToDecrypt.collectAsState()

    val hasRunningJobs by model.hasRunningJobs.collectAsState(false)
    val canDecrypt = fileToDecrypt != null && !hasRunningJobs
            && !fw.isNullOrBlank() && !modelModel.isNullOrBlank() && !region.isNullOrBlank()
    val statusText by model.statusText.collectAsState()

    val canChangeOption = !hasRunningJobs

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var decryptKey by model.decryptionKey.collectAsImmediateMutableState()
    var showingDecryptHelpDialog by remember { mutableStateOf(false) }

    ColumnScrollbarNew(
        state = scrollState,
        thumbColor = ThemeConstants.Colors.scrollbarUnselected,
        thumbSelectedColor = ThemeConstants.Colors.scrollbarSelected,
        alwaysShowScrollBar = true,
        padding = ThemeConstants.Dimensions.scrollbarPadding,
        thickness = ThemeConstants.Dimensions.scrollbarThickness,
        selectionMode = ScrollbarSelectionMode.Disabled,
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(8.dp)
                .verticalScroll(scrollState),
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                val constraints = constraints

                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HybridButton(
                        onClick = {
                            model.launchJob {
                                onDecrypt(model)
                            }
                        },
                        enabled = canDecrypt,
                        text = stringResource(MR.strings.decrypt),
                        description = stringResource(MR.strings.decryptFirmware),
                        vectorIcon = painterResource(MR.images.lock_open_outline),
                        parentSize = constraints.maxWidth
                    )
                    Spacer(Modifier.width(8.dp))
                    HybridButton(
                        onClick = {
                            model.launchJob {
                                onOpenFile(model)
                            }
                        },
                        enabled = canChangeOption,
                        text = stringResource(MR.strings.openFile),
                        description = stringResource(MR.strings.openFileDesc),
                        vectorIcon = painterResource(MR.images.open_in_new),
                        parentSize = constraints.maxWidth
                    )
                    Spacer(Modifier.weight(1f))
                    HybridButton(
                        onClick = {
                            scope.launch {
                                eventManager.sendEvent(Event.Decrypt.Finish)
                            }
                            model.endJob("")
                        },
                        enabled = hasRunningJobs,
                        text = stringResource(MR.strings.cancel),
                        description = stringResource(MR.strings.cancel),
                        vectorIcon = painterResource(MR.images.cancel),
                        parentSize = constraints.maxWidth
                    )
                }
            }

            MRFLayout(model, canChangeOption, canChangeOption, showImeiSerial = true)

            SplitComponent(
                startComponent = {
                    val value = fileToDecrypt?.encFile?.getAbsolutePath() ?: ""
                    OutlinedTextField(
                        value = value,
                        onValueChange = {},
                        label = { Text(text = stringResource(MR.strings.file)) },
                        modifier = Modifier
                            .handleFileDrag {
                                if (it != null) {
                                    scope.launch {
                                        val decInfo = DecryptFileInfo(
                                            encFile = it,
                                            decFile = PlatformFile(it.getParent()!!, File(it.getAbsolutePath()).nameWithoutExtension),
                                        )

                                        handleFileInput(model, decInfo)
                                    }
                                }
                            },
                        readOnly = true,
                        singleLine = true,
                        visualTransformation = OffsetCorrectedIdentityTransformation(value),
                    )
                },
                endComponent = {
                    OutlinedTextField(
                        value = decryptKey ?: "",
                        onValueChange = { decryptKey = it },
                        label = { Text(text = stringResource(MR.strings.decryption_key)) },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = { showingDecryptHelpDialog = true },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = stringResource(MR.strings.help),
                                )
                            }
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                startRatio = 0.6,
                endRatio = 0.4,
            )

            AnimatedVisibility(
                visible = hasRunningJobs || statusText.isNotBlank(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Spacer(Modifier.size(8.dp))

                    ProgressInfo(model)
                }
            }

            InWindowAlertDialog(
                showing = showingDecryptHelpDialog,
                title = { Text(text = stringResource(MR.strings.decryption_key)) },
                text = { Text(text = stringResource(MR.strings.decryption_key_help)) },
                buttons = {
                    TextButton(
                        onClick = { showingDecryptHelpDialog = false },
                    ) {
                        Text(text = stringResource(MR.strings.ok))
                    }
                },
                onDismissRequest = { showingDecryptHelpDialog = false },
            )
        }
    }
}
