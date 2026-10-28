package de.kortty.model;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SerialSettingsTest {

    @Test
    void defaultsArePuttysExceptFlowControl() {
        SerialSettings settings = new SerialSettings();

        assertThat(settings.getPortName()).isNull();
        assertThat(settings.getBaudRate()).isEqualTo(9600);
        assertThat(settings.getDataBits()).isEqualTo(8);
        assertThat(settings.getParity()).isEqualTo(SerialSettings.Parity.NONE);
        assertThat(settings.getStopBits()).isEqualTo(SerialSettings.StopBits.ONE);
        assertThat(settings.getFlowControl()).isEqualTo(SerialSettings.FlowControl.NONE);
        assertThat(settings.isAssertDtr()).isTrue();
        assertThat(settings.isAssertRts()).isTrue();
        assertThat(settings.isLocalEcho()).isFalse();
        assertThat(settings.getLineEnding()).isEqualTo(SerialSettings.LineEnding.CR);
        assertThat(settings.frameSummary()).isEqualTo("9600 8N1");
    }

    @Test
    void storedNonsenseFallsBackToUsableValues() {
        SerialSettings settings = new SerialSettings();
        settings.setBaudRate(-5);
        settings.setDataBits(12);
        settings.setParity(null);
        settings.setStopBits(null);
        settings.setFlowControl(null);
        settings.setLineEnding(null);
        settings.setPortName("   ");

        assertThat(settings.getBaudRate()).isEqualTo(SerialSettings.DEFAULT_BAUD_RATE);
        assertThat(settings.getDataBits()).isEqualTo(8);
        assertThat(settings.getParity()).isEqualTo(SerialSettings.Parity.NONE);
        assertThat(settings.getStopBits()).isEqualTo(SerialSettings.StopBits.ONE);
        assertThat(settings.getFlowControl()).isEqualTo(SerialSettings.FlowControl.NONE);
        assertThat(settings.getLineEnding()).isEqualTo(SerialSettings.LineEnding.CR);
        assertThat(settings.getPortName()).isNull();

        settings.setDataBits(2);
        assertThat(settings.getDataBits()).isEqualTo(5);
    }

    @Test
    void frameSummaryNamesRateDataParityAndStop() {
        SerialSettings settings = new SerialSettings();
        settings.setBaudRate(115200);
        settings.setDataBits(7);
        settings.setParity(SerialSettings.Parity.EVEN);
        settings.setStopBits(SerialSettings.StopBits.ONE_POINT_FIVE);

        assertThat(settings.frameSummary()).isEqualTo("115200 7E1.5");
    }

    @Test
    void copyIsEqualButIndependent() {
        SerialSettings source = new SerialSettings();
        source.setPortName("COM3");
        source.setBaudRate(57600);
        source.setFlowControl(SerialSettings.FlowControl.XON_XOFF);
        source.setLocalEcho(true);

        SerialSettings copy = new SerialSettings(source);
        assertThat(copy).isEqualTo(source);
        assertThat(copy.hashCode()).isEqualTo(source.hashCode());

        copy.setBaudRate(9600);
        assertThat(source.getBaudRate()).isEqualTo(57600);
        assertThat(copy).isNotEqualTo(source);
    }

    @Test
    void serialConnectionIsNoNetworkConnectionAndNamesItsPort() {
        ServerConnection connection = new ServerConnection();
        connection.setProtocol(ConnectionProtocol.SERIAL);
        connection.getSerialSettings().setPortName("/dev/ttyUSB0");
        connection.getSerialSettings().setBaudRate(115200);

        assertThat(connection.isSerial()).isTrue();
        assertThat(connection.isLocalShell()).isFalse();
        assertThat(connection.isNetworkConnection()).isFalse();
        assertThat(connection.getDisplayName()).isEqualTo("/dev/ttyUSB0 @ 115200");

        ServerConnection ssh = new ServerConnection();
        assertThat(ssh.isNetworkConnection()).isTrue();
        assertThat(ssh.serialSettingsOrNull()).isNull();
    }
}
