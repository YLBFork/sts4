package org.springframework.ide.vscode.commons.languageserver.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.springframework.ide.vscode.commons.languageserver.reconcile.IProblemCollector;
import org.springframework.ide.vscode.commons.languageserver.reconcile.IReconcileEngine;
import org.springframework.ide.vscode.commons.languageserver.reconcile.ProblemSeverity;
import org.springframework.ide.vscode.commons.languageserver.reconcile.ReconcileProblem;
import org.springframework.ide.vscode.commons.util.Futures;

/**
 * Abstract base class to implement LanguageServer. Bits and pieces copied from
 * the 'JavaLanguageServer' example which seem generally useful / reusable end up in
 * here so we can try to keep the subclass itself more 'clutter free' and focus on
 * what its really doing and not the 'wiring and plumbing'.
 */
public abstract class SimpleLanguageServer implements LanguageServer, LanguageClientAware {

    private static final Logger LOG = Logger.getLogger(SimpleLanguageServer.class.getName());

    private Consumer<MessageParams> showMessage = m -> {};

    private Path workspaceRoot;

	private SimpleTextDocumentService tds;

	private SimpleWorkspaceService workspace;

	private LanguageClient client;

	@Override
	public void connect(LanguageClient client) {
		this.client = client;
	}

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
//    	LOG.info("Initializing");
    	String rootPath = params.getRootPath();
    	if (rootPath==null) {
//	        LOG.warning("workspaceRoot NOT SET");
    	} else {
	        this.workspaceRoot= Paths.get(rootPath).toAbsolutePath().normalize();
//	        LOG.info("workspaceRoot = "+workspaceRoot);
    	}

        InitializeResult result = new InitializeResult();

        ServerCapabilities cap = getServerCapabilities();
        result.setCapabilities(cap);

        return CompletableFuture.completedFuture(result);
    }

    //TODO: What happened to WindowService? Seems to be removed in lsp4j. So how can we show messages?

//    @Override
//    public WindowService getWindowService() {
//        return new WindowService() {
//            @Override
//            public void onShowMessage(Consumer<MessageParams> callback) {
//                showMessage = callback;
//            }
//
//            @Override
//            public void onShowMessageRequest(Consumer<ShowMessageRequestParams> callback) {
//
//            }
//
//            @Override
//            public void onLogMessage(Consumer<MessageParams> callback) {
//
//            }
//        };
//    }

    public void onError(String message, Throwable error) {
        if (error instanceof ShowMessageException)
            showMessage.accept(((ShowMessageException) error).message);
        else {
            LOG.log(Level.SEVERE, message, error);

            MessageParams m = new MessageParams();

            m.setMessage(message);
            m.setType(MessageType.Error);

            showMessage.accept(m);
        }
    }

	protected abstract ServerCapabilities getServerCapabilities();

    @Override
    public CompletableFuture<Void> shutdown() {
    	return Futures.of(null);
    }

    @Override
    public void exit() {
    }


	public Path getWorkspaceRoot() {
		return workspaceRoot;
	}

	@Override
	public synchronized SimpleTextDocumentService getTextDocumentService() {
		if (tds==null) {
			tds = createTextDocumentService();
		}
		return tds;
	}

	protected SimpleTextDocumentService createTextDocumentService() {
		return new SimpleTextDocumentService(this);
	}

	public SimpleWorkspaceService createWorkspaceService() {
		return new SimpleWorkspaceService();
	}

	@Override
	public synchronized SimpleWorkspaceService getWorkspaceService() {
		if (workspace==null) {
			workspace = createWorkspaceService();
		}
		return workspace;
	}

	/**
	 * Convenience method. Subclasses can call this to use a {@link IReconcileEngine} ported
	 * from old STS codebase to validate a given {@link TextDocument} and publish Diagnostics.
	 */
	protected void validateWith(TextDocument doc, IReconcileEngine engine) {

		SimpleTextDocumentService documents = getTextDocumentService();
		IProblemCollector problems = new IProblemCollector() {

			private List<Diagnostic> diagnostics = new ArrayList<>();

			@Override
			public void endCollecting() {
				documents.publishDiagnostics(doc, diagnostics);
			}

			@Override
			public void beginCollecting() {
				diagnostics.clear();
			}

			@Override
			public void accept(ReconcileProblem problem) {
				DiagnosticSeverity severity = getDiagnosticSeverity(problem);
				if (severity!=null) {
					Diagnostic d = new Diagnostic();
					d.setCode(problem.getCode());
					d.setMessage(problem.getMessage());
					d.setRange(doc.toRange(problem.getOffset(), problem.getLength()));
					d.setSeverity(severity);
					diagnostics.add(d);
				}
			}

			private DiagnosticSeverity getDiagnosticSeverity(ReconcileProblem problem) {
				ProblemSeverity severity = problem.getType().getDefaultSeverity();
				switch (severity) {
				case ERROR:
					return DiagnosticSeverity.Error;
				case WARNING:
					return DiagnosticSeverity.Warning;
				case IGNORE:
					return null;
				default:
					throw new IllegalStateException("Bug! Missing switch case?");
				}
			}
		};
		engine.reconcile(doc, problems);
	}

	public LanguageClient getClient() {
		return client;
	}
}
