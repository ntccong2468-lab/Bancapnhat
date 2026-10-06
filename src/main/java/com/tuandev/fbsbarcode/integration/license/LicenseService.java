package com.tuandev.fbsbarcode.integration.license;

import com.tuandev.fbsbarcode.BuildConfig;
import com.tuandev.fbsbarcode.integration.license.LicenseApiClient.LicenseApiException;
import com.tuandev.fbsbarcode.integration.license.LicenseApiClient.LicenseCheckResponse;
import com.tuandev.fbsbarcode.integration.license.LicenseState.LicenseStatus;
import com.tuandev.fbsbarcode.shared.AppPaths;
import com.tuandev.fbsbarcode.shared.ConfigService;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nguồn sự thật duy nhất về trạng thái license trong app. Trạng thái thuê bao nằm trên
 * license-server; class này chỉ hỏi server, cache license file đã ký để chạy offline có
 * thời hạn ({@link #GRACE_MS}), và chặn lùi đồng hồ hệ thống.
 */
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    /** Thời gian tối đa được chạy offline kể từ lần xác thực server thành công cuối. */
    static final long GRACE_MS = TimeUnit.DAYS.toMillis(14);

    /** Dung sai lệch giờ trước khi coi là chỉnh lùi đồng hồ. */
    static final long CLOCK_SKEW_MS = TimeUnit.HOURS.toMillis(2);

    private static final long REVALIDATE_INTERVAL_HOURS = 6;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "license-revalidation");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static volatile LicenseService instance;

    public static LicenseService getInstance() {
        LicenseService result = instance;
        if (result == null) {
            synchronized (LicenseService.class) {
                result = instance;
                if (result == null) {
                    result =
                            new LicenseService(
                                    new LicenseApiClient(),
                                    new LicenseFileVerifier(),
                                    new LicenseStorage(AppPaths.licenseFile()),
                                    DeviceFingerprint::get,
                                    System::currentTimeMillis);
                    instance = result;
                }
            }
        }
        return result;
    }

    private final LicenseApiClient apiClient;
    private final LicenseFileVerifier verifier;
    private final LicenseStorage storage;
    private final Supplier<String> fingerprintSupplier;
    private final LongSupplier clock;
    private final CopyOnWriteArrayList<Consumer<LicenseState>> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicBoolean revalidationStarted = new AtomicBoolean(false);

    private volatile LicenseState state = LicenseState.of(LicenseStatus.NOT_ACTIVATED);

    LicenseService(
            LicenseApiClient apiClient,
            LicenseFileVerifier verifier,
            LicenseStorage storage,
            Supplier<String> fingerprintSupplier,
            LongSupplier clock) {
        this.apiClient = apiClient;
        this.verifier = verifier;
        this.storage = storage;
        this.fingerprintSupplier = fingerprintSupplier;
        this.clock = clock;
    }

    public LicenseState getState() {
        return state;
    }

    public void addListener(Consumer<LicenseState> listener) {
        listeners.add(listener);
    }

    /**
     * Kích hoạt máy này với key do khách nhập. Gọi từ background thread; ném
     * {@link LicenseApiException} (key sai, vượt số máy) hoặc {@link IOException} (mất mạng)
     * để UI hiển thị thông báo tương ứng.
     */
    public LicenseState activate(String licenseKey)
            throws IOException, LicenseApiException {
        String trimmedKey = licenseKey == null ? "" : licenseKey.trim();
        LicenseCheckResponse response =
                apiClient.activate(
                        trimmedKey,
                        fingerprintSupplier.get(),
                        deviceName(),
                        BuildConfig.getAppVersion());
        ConfigService.setLicenseKey(trimmedKey);
        return applyServerResponse(response);
    }

    /**
     * Xác thực lại với server; khi mất mạng thì đánh giá offline từ license file cache.
     * An toàn để gọi từ background thread bất kỳ lúc nào.
     */
    public LicenseState refresh() {
        String licenseKey = ConfigService.getLicenseKey();
        if (licenseKey == null || licenseKey.isBlank()) {
            return updateState(LicenseState.of(LicenseStatus.NOT_ACTIVATED));
        }
        String fingerprint = fingerprintSupplier.get();
        try {
            return applyServerResponse(
                    apiClient.validate(licenseKey, fingerprint, BuildConfig.getAppVersion()));
        } catch (LicenseApiException e) {
            return updateState(handleApiError(licenseKey, fingerprint, e));
        } catch (IOException e) {
            log.info("License server không truy cập được, đánh giá offline: {}", e.getMessage());
            return updateState(evaluateOffline());
        }
    }

    /** Gỡ máy này khỏi license (best-effort với server) và xóa dữ liệu license cục bộ. */
    public LicenseState deactivateCurrentDevice() {
        String licenseKey = ConfigService.getLicenseKey();
        if (licenseKey != null && !licenseKey.isBlank()) {
            try {
                apiClient.deactivate(licenseKey, fingerprintSupplier.get());
            } catch (IOException | LicenseApiException e) {
                log.warn("Không gỡ được máy trên server (sẽ gỡ cục bộ): {}", e.getMessage());
            }
        }
        ConfigService.setLicenseKey("");
        storage.clear();
        return updateState(LicenseState.of(LicenseStatus.NOT_ACTIVATED));
    }

    /** Xác thực định kỳ chạy nền (idempotent — chỉ khởi động một lần). */
    public void startBackgroundRevalidation() {
        if (revalidationStarted.compareAndSet(false, true)) {
            SCHEDULER.scheduleWithFixedDelay(
                    () -> {
                        try {
                            refresh();
                        } catch (RuntimeException e) {
                            log.warn("Lỗi khi xác thực license định kỳ", e);
                        }
                    },
                    REVALIDATE_INTERVAL_HOURS,
                    REVALIDATE_INTERVAL_HOURS,
                    TimeUnit.HOURS);
        }
    }

    private LicenseState applyServerResponse(LicenseCheckResponse response) {
        Optional<LicensePayload> payload = verifier.verify(response.licenseFile());
        if (payload.isEmpty()) {
            // Server trả về file không verify được bằng public key nhúng trong app
            // → không tin phản hồi này.
            log.warn("License file từ server không verify được bằng public key của app");
            return updateState(LicenseState.of(LicenseStatus.INVALID));
        }
        storage.save(response.licenseFile());
        LicenseStatus status =
                "valid".equals(payload.get().status()) ? LicenseStatus.VALID : LicenseStatus.EXPIRED;
        return updateState(new LicenseState(status, payload.get(), null));
    }

    private LicenseState handleApiError(
            String licenseKey, String fingerprint, LicenseApiException e) {
        switch (e.code()) {
            case LicenseApiException.CODE_DEVICE_NOT_ACTIVATED -> {
                // Máy bị gỡ khỏi license (đổi máy/admin gỡ) — thử kích hoạt lại tự động.
                try {
                    LicenseCheckResponse response =
                            apiClient.activate(
                                    licenseKey, fingerprint, deviceName(), BuildConfig.getAppVersion());
                    return applyServerResponse(response);
                } catch (LicenseApiException nested) {
                    return errorState(nested);
                } catch (IOException nested) {
                    return evaluateOffline();
                }
            }
            default -> {
                return errorState(e);
            }
        }
    }

    private LicenseState errorState(LicenseApiException e) {
        LicenseStatus status =
                switch (e.code()) {
                    case LicenseApiException.CODE_DEVICE_LIMIT_REACHED -> LicenseStatus.DEVICE_LIMIT;
                    default -> LicenseStatus.INVALID;
                };
        if (status == LicenseStatus.INVALID) {
            // Key sai/bị thu hồi: file cache cũ không còn giá trị chứng minh.
            storage.clear();
        }
        return new LicenseState(status, null, e.getMessage());
    }

    private LicenseState evaluateOffline() {
        Optional<LicensePayload> payload = storage.load().flatMap(verifier::verify);
        if (payload.isEmpty()) {
            return LicenseState.of(LicenseStatus.NETWORK_ERROR);
        }
        LicensePayload p = payload.get();
        long now = clock.getAsLong();
        if (now + CLOCK_SKEW_MS < p.issuedAt()) {
            return new LicenseState(LicenseStatus.CLOCK_TAMPERED, p, null);
        }
        if (now >= p.expiresAt()) {
            return new LicenseState(LicenseStatus.EXPIRED, p, null);
        }
        if (now - p.issuedAt() > GRACE_MS) {
            return new LicenseState(LicenseStatus.NETWORK_ERROR, p, null);
        }
        return new LicenseState(LicenseStatus.OFFLINE_GRACE, p, null);
    }

    private LicenseState updateState(LicenseState newState) {
        this.state = newState;
        for (Consumer<LicenseState> listener : listeners) {
            try {
                listener.accept(newState);
            } catch (RuntimeException e) {
                log.warn("License listener ném lỗi", e);
            }
        }
        return newState;
    }

    private static String deviceName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            name = System.getProperty("user.name", "unknown");
        }
        return name;
    }
}
