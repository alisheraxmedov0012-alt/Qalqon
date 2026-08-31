package uz.faceguard.app.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import uz.faceguard.app.core.util.Validation

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
    errorRes: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        isError = errorRes != null,
        supportingText = { errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
    )
}

/** Accepts any pasted phone format; forwards digits-only to the ViewModel. */
@Composable
fun AppPhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
    errorRes: Int? = null,
) {
    AppTextField(
        value = value,
        onValueChange = { raw -> onValueChange(Validation.normalizePhone(raw).take(Validation.MAX_PHONE_DIGITS)) },
        labelRes = labelRes,
        modifier = modifier,
        errorRes = errorRes,
        keyboardType = KeyboardType.Phone,
    )
}

@Composable
fun AppPinField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
    errorRes: Int? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter(Char::isDigit).take(Validation.MAX_PIN_DIGITS)) },
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        isError = errorRes != null,
        supportingText = { errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier,
    )
}

@Composable
fun AppLoadingButton(
    labelRes: Int,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Button(
        onClick = onClick,
        enabled = !loading,
        modifier = modifier,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text(stringResource(labelRes))
        }
    }
}
