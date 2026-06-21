import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;

public class Main {

    static class BackgroundJob {
        int id;
        Process process;
        String commandStr;

        BackgroundJob(int id, Process process, String commandStr) {
            this.id = id;
            this.process = process;
            this.commandStr = commandStr;
        }
    }

    static class Command {
        List<String> tokens = new ArrayList<>();
        String outFile = null;
        String errFile = null;
        boolean appendOut = false;
        boolean appendErr = false;
        boolean isBuiltin = false;

        Command(List<String> rawTokens) {
            for (int i = 0; i < rawTokens.size(); i++) {
                String t = rawTokens.get(i);
                if (t.equals(">") || t.equals("1>")) {
                    if (i + 1 < rawTokens.size()) outFile = rawTokens.get(++i);
                } else if (t.equals(">>") || t.equals("1>>")) {
                    if (i + 1 < rawTokens.size()) { outFile = rawTokens.get(++i); appendOut = true; }
                } else if (t.equals("2>")) {
                    if (i + 1 < rawTokens.size()) errFile = rawTokens.get(++i);
                } else if (t.equals("2>>")) {
                    if (i + 1 < rawTokens.size()) { errFile = rawTokens.get(++i); appendErr = true; }
                } else {
                    tokens.add(t);
                }
            }
            if (!tokens.isEmpty()) {
                isBuiltin = Main.isBuiltin(tokens.get(0));
            }
        }
    }

    private static final List<BackgroundJob> activeJobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        String currentDir = System.getProperty("user.dir");

        while (true) {
            reapCompletedJobs();

            System.out.print("$ ");
            System.out.flush();

            if (!sc.hasNextLine()) break;
            String input = sc.nextLine();
            if (input.isEmpty()) continue;
            if (input.equals("exit")) break;

            ArrayList<String> tokens = new ArrayList<>();
            StringBuilder sb = new StringBuilder();

            boolean inSingle = false;
            boolean inDouble = false;

            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);

                if (inSingle) {
                    if (c == '\'') inSingle = false;
                    else sb.append(c);
                    continue;
                }

                if (inDouble) {
                    if (c == '\\') {
                        if (i + 1 < input.length()) {
                            char n = input.charAt(i + 1);
                            if (n == '"' || n == '\\' || n == '$' || n == '`') {
                                sb.append(n);
                                i++;
                            } else {
                                sb.append('\\');
                            }
                        } else {
                            sb.append('\\');
                        }
                        continue;
                    }

                    if (c == '"') {
                        inDouble = false;
                        continue;
                    }

                    sb.append(c);
                    continue;
                }

                if (c == '\'') {
                    inSingle = true;
                    continue;
                }

                if (c == '"') {
                    inDouble = true;
                    continue;
                }

                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        sb.append(input.charAt(i + 1));
                        i++;
                    }
                    continue;
                }

                if (c == ' ') {
                    if (sb.length() > 0) {
                        tokens.add(sb.toString());
                        sb.setLength(0);
                    }
                } else {
                    sb.append(c);
                }
            }

            if (sb.length() > 0) tokens.add(sb.toString());
            if (tokens.isEmpty()) continue;

            boolean isBackground = false;
            if (tokens.get(tokens.size() - 1).equals("&")) {
                isBackground = true;
                tokens.remove(tokens.size() - 1);
            }

            if (tokens.isEmpty()) continue;

            List<List<String>> rawCommands = new ArrayList<>();
            List<String> cur = new ArrayList<>();
            for (String t : tokens) {
                if (t.equals("|")) {
                    rawCommands.add(cur);
                    cur = new ArrayList<>();
                } else {
                    cur.add(t);
                }
            }
            rawCommands.add(cur);

            List<Command> pipeline = new ArrayList<>();
            for (List<String> raw : rawCommands) {
                if (!raw.isEmpty()) {
                    pipeline.add(new Command(raw));
                }
            }

            if (pipeline.isEmpty() || pipeline.get(0).tokens.isEmpty()) continue;

            for (Command c : pipeline) {
                if (c.outFile != null) createOrPrepareFile(currentDir, c.outFile, c.appendOut);
                if (c.errFile != null) createOrPrepareFile(currentDir, c.errFile, c.appendErr);
            }

            byte[] currentInput = new byte[0];
            boolean hasInput = false;
            List<ProcessBuilder> extBlock = new ArrayList<>();
            List<Command> extBlockCmds = new ArrayList<>();

            for (int i = 0; i < pipeline.size(); i++) {
                Command c = pipeline.get(i);
                if (c.tokens.isEmpty()) continue;

                if (c.isBuiltin) {
                    if (!extBlock.isEmpty()) {
                        currentInput = runExternalBlock(extBlock, extBlockCmds, currentInput, hasInput, currentDir);
                        extBlock.clear();
                        extBlockCmds.clear();
                        hasInput = true;
                    }

                    ByteArrayOutputStream outCapture = new ByteArrayOutputStream();
                    ByteArrayOutputStream errCapture = new ByteArrayOutputStream();

                    executeBuiltin(c.tokens, new ByteArrayInputStream(currentInput), outCapture, errCapture, currentDir);

                    byte[] errBytes = errCapture.toByteArray();
                    if (c.errFile != null) {
                        if (errBytes.length > 0) {
                            File f = new File(c.errFile);
                            if (!f.isAbsolute()) f = new File(currentDir, c.errFile);
                            try (FileOutputStream fos = new FileOutputStream(f, true)) { fos.write(errBytes); }
                        }
                    } else if (errBytes.length > 0) {
                        System.err.write(errBytes);
                        System.err.flush();
                    }

                    byte[] outBytes = outCapture.toByteArray();
                    if (c.outFile != null) {
                        if (outBytes.length > 0) {
                            File f = new File(c.outFile);
                            if (!f.isAbsolute()) f = new File(currentDir, c.outFile);
                            try (FileOutputStream fos = new FileOutputStream(f, true)) { fos.write(outBytes); }
                        }
                        currentInput = new byte[0];
                    } else {
                        currentInput = outBytes;
                    }
                    hasInput = true;

                    if (c.tokens.get(0).equals("cd") && errBytes.length == 0) {
                        String path = c.tokens.size() > 1 ? c.tokens.get(1) : "";
                        if (path.equals("~")) path = System.getenv("HOME");
                        File f = new File(path);
                        if (!f.isAbsolute()) f = new File(currentDir, path);
                        if (f.exists() && f.isDirectory()) currentDir = f.getCanonicalPath();
                    }
                } else {
                    ProcessBuilder pb = new ProcessBuilder(c.tokens).directory(new File(currentDir));
                    if (c.errFile != null) {
                        File targetErrFile = new File(c.errFile);
                        if (!targetErrFile.isAbsolute()) targetErrFile = new File(currentDir, c.errFile);
                        pb.redirectError(c.appendErr ? ProcessBuilder.Redirect.appendTo(targetErrFile) : ProcessBuilder.Redirect.to(targetErrFile));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    extBlock.add(pb);
                    extBlockCmds.add(c);
                }
            }

            if (!extBlock.isEmpty()) {
                runExternalBlockLast(extBlock, extBlockCmds, currentInput, hasInput, currentDir, isBackground, String.join(" ", tokens));
            } else {
                if (currentInput.length > 0) {
                    System.out.write(currentInput);
                    System.out.flush();
                }
            }
        }

        sc.close();
    }

    private static byte[] runExternalBlock(List<ProcessBuilder> pbs, List<Command> cmds, byte[] initialInput, boolean hasInput, String currentDir) throws Exception {
        try {
            ProcessBuilder lastPb = pbs.get(pbs.size() - 1);
            lastPb.redirectOutput(ProcessBuilder.Redirect.PIPE);

            List<Process> processes = ProcessBuilder.startPipeline(pbs);

            if (hasInput && initialInput.length > 0) {
                Process first = processes.get(0);
                try (OutputStream out = first.getOutputStream()) {
                    out.write(initialInput);
                    out.flush();
                } catch (Exception e) {}
            }

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Process lastProcess = processes.get(processes.size() - 1);
            try (InputStream in = lastProcess.getInputStream()) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) != -1) captured.write(buf, 0, r);
            }

            for (Process p : processes) p.waitFor();
            return captured.toByteArray();
        } catch (Exception e) {
            write(currentDir, cmds.get(0).errFile, cmds.get(0).tokens.get(0) + ": command not found", true);
            return new byte[0];
        }
    }

    private static void runExternalBlockLast(List<ProcessBuilder> pbs, List<Command> cmds, byte[] initialInput, boolean hasInput, String currentDir, boolean isBackground, String rawCommandStr) throws Exception {
        try {
            Command lastCmd = cmds.get(cmds.size() - 1);
            ProcessBuilder lastPb = pbs.get(pbs.size() - 1);

            if (lastCmd.outFile != null) {
                File targetFile = new File(lastCmd.outFile);
                if (!targetFile.isAbsolute()) targetFile = new File(currentDir, lastCmd.outFile);
                lastPb.redirectOutput(lastCmd.appendOut ? ProcessBuilder.Redirect.appendTo(targetFile) : ProcessBuilder.Redirect.to(targetFile));
            } else {
                lastPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            List<Process> processes = ProcessBuilder.startPipeline(pbs);

            if (hasInput && initialInput.length > 0) {
                Process first = processes.get(0);
                try (OutputStream out = first.getOutputStream()) {
                    out.write(initialInput);
                    out.flush();
                } catch (Exception e) {}
            }

            if (isBackground) {
                Process lastProcess = processes.get(processes.size() - 1);
                int jobId = getLowestAvailableJobId();
                System.out.println("[" + jobId + "] " + lastProcess.pid());
                System.out.flush();
                activeJobs.add(new BackgroundJob(jobId, lastProcess, rawCommandStr));
            } else {
                for (Process p : processes) p.waitFor();
                System.out.flush();
                System.err.flush();
            }
        } catch (Exception e) {
            write(currentDir, cmds.get(0).errFile, cmds.get(0).tokens.get(0) + ": command not found", true);
        }
    }

    static boolean isBuiltin(String cmd) {
        return cmd.equals("echo") || cmd.equals("pwd") || cmd.equals("cd") || cmd.equals("type") || cmd.equals("jobs");
    }

    private static void executeBuiltin(List<String> cmdTokens, InputStream stdin, OutputStream stdout, OutputStream stderr, String currentDir) throws Exception {
        PrintStream out = new PrintStream(stdout);
        PrintStream err = new PrintStream(stderr);
        String command = cmdTokens.get(0);

        if (command.equals("jobs")) {
            int targetId = -1;
            if (cmdTokens.size() > 1 && cmdTokens.get(1).startsWith("%")) {
                try { targetId = Integer.parseInt(cmdTokens.get(1).substring(1)); } catch (NumberFormatException e) {}
            }
            int count = 0;
            int total = activeJobs.size();
            Iterator<BackgroundJob> it = activeJobs.iterator();
            while (it.hasNext()) {
                BackgroundJob job = it.next();
                count++;
                if (targetId == -1 || job.id == targetId) {
                    String sign = " ";
                    if (count == total) sign = "+";
                    else if (count == total - 1) sign = "-";

                    if (!job.process.isAlive()) {
                        out.println("[" + job.id + "]" + sign + "  Done                    " + job.commandStr);
                        it.remove();
                    } else {
                        out.println("[" + job.id + "]" + sign + "  Running                 " + job.commandStr + " &");
                    }
                } else if (!job.process.isAlive()) {
                    it.remove();
                }
            }
        } else if (command.equals("echo")) {
            String output = String.join(" ", cmdTokens.subList(1, cmdTokens.size()));
            out.println(output);
        } else if (command.equals("pwd")) {
            out.println(currentDir);
        } else if (command.equals("cd")) {
            String path = cmdTokens.size() > 1 ? cmdTokens.get(1) : "";
            if (path.equals("~")) path = System.getenv("HOME");
            File f = new File(path);
            if (!f.isAbsolute()) f = new File(currentDir, path);
            if (f.exists() && f.isDirectory()) {
                System.setProperty("user.dir", f.getCanonicalPath());
            } else {
                err.println("cd: " + path + ": No such file or directory");
            }
        } else if (command.equals("type")) {
            String name = cmdTokens.get(1);
            if (isBuiltin(name) || name.equals("exit")) {
                out.println(name + " is a shell builtin");
            } else {
                String[] paths = System.getenv("PATH").split(":");
                boolean found = false;
                for (String p : paths) {
                    File f = new File(p, name);
                    if (f.exists() && f.canExecute()) {
                        out.println(name + " is " + f.getAbsolutePath());
                        found = true;
                        break;
                    }
                }
                if (!found) out.println(name + ": not found");
            }
        }
        out.flush();
        err.flush();
    }

    private static int getLowestAvailableJobId() {
        List<Integer> sortedIds = new ArrayList<>();
        for (BackgroundJob job : activeJobs) sortedIds.add(job.id);
        Collections.sort(sortedIds);
        int candidate = 1;
        for (int id : sortedIds) {
            if (id == candidate) candidate++;
            else if (id > candidate) break;
        }
        return candidate;
    }

    private static void reapCompletedJobs() {
        Iterator<BackgroundJob> it = activeJobs.iterator();
        List<BackgroundJob> finished = new ArrayList<>();
        while (it.hasNext()) {
            BackgroundJob job = it.next();
            if (!job.process.isAlive()) {
                finished.add(job);
                it.remove();
            }
        }
        int totalActive = activeJobs.size();
        for (int i = 0; i < finished.size(); i++) {
            BackgroundJob job = finished.get(i);
            String sign = " ";
            int virtualIndex = totalActive + i;
            if (virtualIndex == totalActive + finished.size() - 1) sign = "+";
            else if (virtualIndex == totalActive + finished.size() - 2) sign = "-";
            System.out.println("[" + job.id + "]" + sign + "  Done                    " + job.commandStr);
            System.out.flush();
        }
    }

    private static void createOrPrepareFile(String dir, String file, boolean append) throws Exception {
        File targetFile = new File(file);
        if (!targetFile.isAbsolute()) targetFile = new File(dir, file);
        if (targetFile.getParentFile() != null) targetFile.getParentFile().mkdirs();
        if (!append) {
            new FileOutputStream(targetFile).close();
        } else {
            if (!targetFile.exists()) targetFile.createNewFile();
        }
    }

    private static void write(String dir, String file, String output, boolean isStderr) throws Exception {
        String formattedOutput = output + "\n";
        if (file != null) {
            File targetFile = new File(file);
            if (!targetFile.isAbsolute()) targetFile = new File(dir, file);
            FileOutputStream fos = new FileOutputStream(targetFile, true);
            fos.write(formattedOutput.getBytes());
            fos.close();
        } else {
            if (isStderr) System.err.print(formattedOutput);
            else System.out.print(formattedOutput);
        }
    }
}