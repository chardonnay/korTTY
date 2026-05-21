package de.kortty.update;

public record UpdateCheckResult(Status status, AvailableUpdate update, String message) {

    public enum Status {
        UPDATE_AVAILABLE,
        NO_UPDATE,
        NO_COMPATIBLE_ASSET,
        FAILED
    }

    public static UpdateCheckResult updateAvailable(AvailableUpdate update) {
        return new UpdateCheckResult(Status.UPDATE_AVAILABLE, update, null);
    }

    public static UpdateCheckResult noUpdate(String message) {
        return new UpdateCheckResult(Status.NO_UPDATE, null, message);
    }

    public static UpdateCheckResult noCompatibleAsset(String message) {
        return new UpdateCheckResult(Status.NO_COMPATIBLE_ASSET, null, message);
    }

    public static UpdateCheckResult failed(String message) {
        return new UpdateCheckResult(Status.FAILED, null, message);
    }
}
