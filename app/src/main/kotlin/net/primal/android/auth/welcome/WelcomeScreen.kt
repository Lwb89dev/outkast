package net.primal.android.auth.welcome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.primal.android.R
import net.primal.android.auth.compose.OnboardingButton
import net.primal.android.theme.AppTheme
import net.primal.android.theme.CourierPrimeFontFamily
import net.primal.android.theme.PrimalTheme
import net.primal.android.theme.domain.PrimalTheme

@Composable
fun WelcomeScreen(callbacks: WelcomeContract.ScreenCallbacks) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colorScheme.background)
            .semantics { testTagsAsResourceId = true },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                modifier = Modifier
                    .size(156.dp)
                    .clip(RoundedCornerShape(38.dp)),
                painter = painterResource(id = R.drawable.outkast_icon),
                contentDescription = stringResource(id = R.string.app_name),
            )
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = stringResource(id = R.string.onboarding_welcome_eyebrow),
                style = AppTheme.typography.labelMedium.copy(
                    fontFamily = CourierPrimeFontFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                ),
                color = AppTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.onboarding_welcome_title),
                style = AppTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                ),
                color = AppTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                modifier = Modifier.widthIn(max = 460.dp),
                text = stringResource(id = R.string.onboarding_welcome_tagline),
                style = AppTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = AppTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                modifier = Modifier.widthIn(max = 420.dp),
                text = stringResource(id = R.string.onboarding_welcome_description),
                style = AppTheme.typography.bodyLarge,
                color = AppTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(26.dp))
            WelcomeFeatureCard()
            Spacer(modifier = Modifier.height(30.dp))
            OnboardingButton(
                text = stringResource(id = R.string.welcome_create_account_button_title),
                onClick = callbacks.onGetStartedClick,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OnboardingButton(
                text = stringResource(id = R.string.welcome_sign_in_button_title),
                onClick = callbacks.onExistingIdentityClick,
                secondary = true,
            )
        }
    }
}

@Composable
private fun WelcomeFeatureCard() {
    Surface(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = AppTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, AppTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WelcomeFeature(text = stringResource(id = R.string.onboarding_welcome_feature_relays))
            WelcomeFeature(text = stringResource(id = R.string.onboarding_welcome_feature_tor))
            WelcomeFeature(text = stringResource(id = R.string.onboarding_welcome_feature_local))
        }
    }
}

@Composable
private fun WelcomeFeature(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(AppTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Text(
            modifier = Modifier.padding(start = 14.dp),
            text = text,
            style = AppTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = AppTheme.colorScheme.onSurface,
        )
    }
}

@Preview
@Composable
fun PreviewWelcomeScreen() {
    PrimalTheme(primalTheme = PrimalTheme.OutkastDark) {
        WelcomeScreen(
            callbacks = WelcomeContract.ScreenCallbacks(
                onGetStartedClick = {},
                onExistingIdentityClick = {},
            ),
        )
    }
}
