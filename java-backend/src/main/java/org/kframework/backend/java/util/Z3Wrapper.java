// Copyright (c) 2013-2014 K Team. All Rights Reserved.
package org.kframework.backend.java.util;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.microsoft.z3.Params;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import com.microsoft.z3.Z3Exception;

import org.kframework.kil.loader.Context;
import org.kframework.utils.OS;
import org.kframework.utils.general.GlobalSettings;

import java.io.*;

/**
 * @author Traian
 */
public class Z3Wrapper {

    private static final int Z3_RESTART_LIMIT = 3;
    public static Z3Wrapper Z3_WRAPPER;
    public static Z3Wrapper instance(Context context) {
        if (Z3_WRAPPER == null) {
            Z3_WRAPPER = new Z3Wrapper(context);
        }
        return Z3_WRAPPER;
    }

    public final String SMT_PRELUDE;
    private String logic;
    private final Context context;

    public Z3Wrapper(Context context) {
        this.context = context;

        String s = "";
        logic = "";
        try {
            if (context.krunOptions.experimental.smtPrelude() != null) {
                s = Files.toString(context.krunOptions.experimental.smtPrelude(), Charsets.UTF_8);
                logic = context.krunOptions.experimental.smtPrelude().getName().equals("floating_point.smt2") ? "QF_FPA" : null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        SMT_PRELUDE = s;
    }

    public boolean checkQuery(String query, int timeout, Context context) {
        if (context.krunOptions.experimental.z3Executable) {
            return checkQueryWithExternalProcess(query, timeout);
        } else {
            return checkQueryWithLibrary(query, timeout);
        }
    }

    public boolean checkQueryWithLibrary(String query, int timeout) {
        boolean result = false;
        try {
            com.microsoft.z3.Context context = new com.microsoft.z3.Context();
            Solver solver = logic != null ? context.mkSolver(logic) : context.mkSolver();
            Params params = context.mkParams();
            params.add("timeout", timeout);
            solver.setParameters(params);
            solver.add(context.parseSMTLIB2String(SMT_PRELUDE + query, null, null, null, null));
            result = solver.check() == Status.UNSATISFIABLE;
            context.dispose();
        } catch (Z3Exception e) {
            GlobalSettings.kem.registerCriticalWarning(
                    "failed to translate smtlib expression:\n" + SMT_PRELUDE + query);
        }
        return result;
    }

    public boolean checkQueryWithExternalProcess(String query, int timeout) {
        String result = "";
        try {
            for (int i = 0; i < Z3_RESTART_LIMIT; i++) {
                ProcessBuilder pb = new ProcessBuilder(
                        OS.current().getNativeExecutable("z3").getAbsolutePath(),
                        "-in",
                        "-smt2",
                        "-t:" + timeout);
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                Process z3Process = pb.start();
                BufferedWriter input = new BufferedWriter(new OutputStreamWriter(
                    z3Process.getOutputStream()));
                BufferedReader output = new BufferedReader(new InputStreamReader(
                    z3Process.getInputStream()));
                input.write(SMT_PRELUDE + query + "(check-sat)\n");
                input.flush();
                result = output.readLine();
                z3Process.destroy();

                if (result != null) {
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (result == null) {
            result = "unknown";
            if (context.globalOptions.debug) {
                System.err.println("Z3 crashed on query:\n" + SMT_PRELUDE + query + "(check-sat)\n");
            }
        }
        return result.equals("unsat");
    }
}
