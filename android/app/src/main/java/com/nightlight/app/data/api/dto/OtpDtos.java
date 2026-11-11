package com.nightlight.app.data.api.dto;

/** Payloads for the email OTP auth flow. */
public final class OtpDtos {

    private OtpDtos() {
    }

    public static final class RequestOtpRequest {
        public String email;

        public RequestOtpRequest(String email) {
            this.email = email;
        }
    }

    public static final class RequestOtpResponse {
        public int expiresIn;
        public int resendAfter;
        public boolean emailSent;
        public boolean devDelivery;
    }

    public static final class VerifyOtpRequest {
        public String email;
        public String otp;

        public VerifyOtpRequest(String email, String otp) {
            this.email = email;
            this.otp = otp;
        }
    }

    public static final class VerifyOtpUser {
        public String id;
        public String email;
        public String createdAt;
        /** Permanent backend username (one per Firebase UID). Nullable on old builds. */
        public String username;
        public String displayName;
        public String photoUrl;
    }

    public static final class VerifyOtpResponse {
        public String token;
        public VerifyOtpUser user;
        public PreferencesData preferences;
    }

    public static final class PreferencesData {
        public java.util.List<String> languages;
        public java.util.List<String> categories;
    }

    public static final class SavePreferencesRequest {
        public java.util.List<String> languages;
        public java.util.List<String> categories;

        public SavePreferencesRequest(java.util.List<String> languages, java.util.List<String> categories) {
            this.languages = languages;
            this.categories = categories;
        }
    }

    // ---- Email + password auth ----

    public static final class PasswordRegisterRequest {
        public String email;
        public String password;

        public PasswordRegisterRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    public static final class PasswordRegisterResponse {
        public RegisterUser user;
        public RequestOtpResponse verification;
    }

    public static final class RegisterUser {
        public String email;
    }

    public static final class LoginRequest {
        public String email;
        public String password;

        public LoginRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    /** Same payload as the OTP verify response (token + user). */
    public static final class LoginResponse {
        public String token;
        public VerifyOtpUser user;
    }

    /** Swaps a verified Firebase ID token for a NightLight session. */
    public static final class FirebaseExchangeRequest {
        public String idToken;

        public FirebaseExchangeRequest(String idToken) {
            this.idToken = idToken;
        }
    }

    /** Same payload as the password login response. */
    public static final class FirebaseExchangeResponse {
        public String token;
        public VerifyOtpUser user;
    }

    public static final class ForgotPasswordRequest {
        public String email;

        public ForgotPasswordRequest(String email) {
            this.email = email;
        }
    }

    public static final class ResetVerifyRequest {
        public String email;
        public String otp;

        public ResetVerifyRequest(String email, String otp) {
            this.email = email;
            this.otp = otp;
        }
    }

    public static final class ResetVerifyResponse {
        public String resetToken;
    }

    public static final class ResetPasswordRequest {
        public String email;
        public String resetToken;
        public String newPassword;

        public ResetPasswordRequest(String email, String resetToken, String newPassword) {
            this.email = email;
            this.resetToken = resetToken;
            this.newPassword = newPassword;
        }
    }
}