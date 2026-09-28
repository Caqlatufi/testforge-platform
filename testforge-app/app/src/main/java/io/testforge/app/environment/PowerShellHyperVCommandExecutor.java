package io.testforge.app.environment;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
class PowerShellHyperVCommandExecutor implements HyperVCommandExecutor {
    private static final String STATE_COMMAND =
            "$ErrorActionPreference='Stop'; $name=[Environment]::GetEnvironmentVariable('TESTFORGE_VM_NAME'); "
                    + "(Get-VM -Name $name -ErrorAction Stop).State.ToString()";
    private static final String START_COMMAND =
            "$ErrorActionPreference='Stop'; $name=[Environment]::GetEnvironmentVariable('TESTFORGE_VM_NAME'); "
                    + "$vm=Get-VM -Name $name -ErrorAction Stop; "
                    + "if ($vm.State -ne 'Running') { Start-VM -VM $vm -ErrorAction Stop | Out-Null }; "
                    + "(Get-VM -Name $name -ErrorAction Stop).State.ToString()";

    @Override
    public boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    @Override
    public String state(String vmName) {
        return execute(vmName, STATE_COMMAND);
    }

    @Override
    public String start(String vmName) {
        return execute(vmName, START_COMMAND);
    }

    private String execute(String vmName, String command) {
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command
        ).redirectErrorStream(true);
        builder.environment().put("TESTFORGE_VM_NAME", vmName);
        try {
            Process process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Hyper-V 操作超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(output.isBlank() ? "Hyper-V 操作失败" : output);
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("无法调用本机 Hyper-V", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Hyper-V 操作被中断", exception);
        }
    }
}
