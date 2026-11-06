/*
 * Faro da Memoria — KAN-96
 * Pantalla de configuración inicial: pegar credenciales de Meta
 * (Wearables Developer Center) y conectar las gafas sin recompilar la app.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.CredentialsStore
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import androidx.compose.ui.res.stringResource

@Composable
fun CredentialsSetupScreen(
    viewModel: WearablesViewModel,
    onSetupDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val activity = LocalActivity.current
  val store = remember { CredentialsStore(context) }
  var applicationId by remember { mutableStateOf(store.applicationId ?: "") }
  var clientToken by remember { mutableStateOf(store.clientToken ?: "") }

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
          text = stringResource(R.string.setup_title),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
      )
      Text(
          text = stringResource(R.string.setup_instructions),
          style = MaterialTheme.typography.bodyMedium,
      )
      OutlinedTextField(
          value = applicationId,
          onValueChange = { applicationId = it },
          label = { Text(stringResource(R.string.setup_application_id)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
      )
      OutlinedTextField(
          value = clientToken,
          onValueChange = { clientToken = it },
          label = { Text(stringResource(R.string.setup_client_token)) },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
      )
      SwitchButton(
          label = stringResource(R.string.setup_save_and_continue),
          enabled = true,
          onClick = {
            if (applicationId.isNotBlank() && clientToken.isNotBlank()) {
              store.saveCredentials(applicationId, clientToken)
            }
            store.markSetupCompleted()
            onSetupDone()
          },
      )
      Text(
          text = stringResource(R.string.setup_skip_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.weight(1f))
    }
  }
}
