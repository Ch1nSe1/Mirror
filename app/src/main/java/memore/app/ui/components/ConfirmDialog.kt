package memore.app.ui.components

import androidx.compose.foundation.layout.Column
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.components.MirrorAlertDialog
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    isDestructive: Boolean = false,
    requireTypedConfirmation: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var typedText by remember(requireTypedConfirmation) { mutableStateOf("") }
    val canConfirm = requireTypedConfirmation == null || typedText == requireTypedConfirmation

    MirrorAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (requireTypedConfirmation != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MemoreLineInput(
                        value = typedText,
                        onValueChange = { typedText = it },
                        placeholder = {
                            Text("输入 \"${requireTypedConfirmation}\" 确认")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MirrorShapes.row
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDestructive)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
        shape = MirrorShapes.menu,
        containerColor = MaterialTheme.colorScheme.surface
    )
}
