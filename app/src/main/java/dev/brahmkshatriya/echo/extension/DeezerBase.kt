package dev.brahmkshatriya.echo.extension

data class DeezerCredentials(
    val arl: String,
    val sid: String,
    val token: String,
    val userId: String,
    val licenseToken: String,
    val email: String,
    val pass: String
)

object DeezerUtils {
    const val deezerLanguage: String = "en"
    const val deezerCountry: String = "US"

    private var _arlExpired: Boolean = false
    val arlExpired: Boolean
        get() = _arlExpired

    fun setArlExpired(expired: Boolean) {
        _arlExpired = expired
    }
}

object DeezerCredentialsHolder {
    private var _credentials: DeezerCredentials? = null
    val credentials: DeezerCredentials?
        get() = _credentials

    fun initialize(credentials: DeezerCredentials) {
        if (_credentials == null) {
            _credentials = credentials
        } else {
            throw IllegalStateException("Credentials are already initialized")
        }
    }

    fun updateCredentials(arl: String? = null, sid: String? = null, token: String? = null, userId: String? = null, licenseToken: String? = null, email: String? = null, pass: String? = null) {
        _credentials = _credentials?.copy(
            arl = arl ?: _credentials!!.arl,
            sid = sid ?: _credentials!!.sid,
            token = token ?: _credentials!!.token,
            userId = userId ?: _credentials!!.userId,
            licenseToken = licenseToken ?: _credentials!!.licenseToken,
            email = email ?: _credentials!!.email,
            pass = pass ?: _credentials!!.pass
        ) ?: throw IllegalStateException("Credentials are not initialized")
    }
}

