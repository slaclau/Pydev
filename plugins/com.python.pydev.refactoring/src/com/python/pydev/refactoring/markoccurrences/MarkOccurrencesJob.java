/**
 * Copyright (c) 2005-2013 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Eclipse Public License (EPL).
 * Please see the license.txt included with this distribution for details.
 * Any modifications to this file must keep this entire header intact.
 */
/*
 * Created on Apr 29, 2006
 */
package com.python.pydev.refactoring.markoccurrences;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.python.pydev.core.MisconfigurationException;
import org.python.pydev.core.docutils.PySelection;
import org.python.pydev.core.log.Log;
import org.python.pydev.editor.PyEdit;
import org.python.pydev.editor.actions.refactoring.PyRefactorAction;
import org.python.pydev.editor.codefolding.PySourceViewer;
import org.python.pydev.editor.refactoring.RefactoringRequest;
import org.python.pydev.parser.jython.SimpleNode;
import org.python.pydev.parser.jython.ast.Name;
import org.python.pydev.parser.visitors.scope.ASTEntry;
import org.python.pydev.shared_core.string.TextSelectionUtils;
import org.python.pydev.shared_ui.editor.BaseEditor;
import org.python.pydev.shared_ui.mark_occurrences.BaseMarkOccurrencesJob;

import com.python.pydev.refactoring.ui.MarkOccurrencesPreferencesPage;
import com.python.pydev.refactoring.wizards.rename.PyRenameEntryPoint;

/**
 * This is a 'low-priority' thread. It acts as a singleton. Requests to mark the occurrences
 * will be forwarded to it, so, it should sleep for a while and then check for a request.
 * 
 * If the request actually happened, it will go on to process it, otherwise it will sleep some more.
 * 
 * @author Fabio
 */
public class MarkOccurrencesJob extends BaseMarkOccurrencesJob {

    protected final static class PyMarkOccurrencesRequest extends MarkOccurrencesRequest {
        public final RefactoringRequest refactoringRequest;
        public final PyRenameEntryPoint pyRenameEntryPoint;

        protected PyMarkOccurrencesRequest(RefactoringRequest refactoringRequest,
                PyRenameEntryPoint pyRenameEntryPoint,
                boolean proceedWithMarkOccurrences) {
            super(proceedWithMarkOccurrences);
            this.refactoringRequest = refactoringRequest;
            this.pyRenameEntryPoint = pyRenameEntryPoint;
        }
    }

    public MarkOccurrencesJob(WeakReference<BaseEditor> editor, TextSelectionUtils ps) {
        super(editor, ps);
    }

    /**
     * @return a tuple with the refactoring request, the processor and a boolean indicating if all pre-conditions succedded.
     * @throws MisconfigurationException 
     */
    @Override
    protected MarkOccurrencesRequest createRequest(BaseEditor baseEditor,
            IDocumentProvider documentProvider, IProgressMonitor monitor) throws BadLocationException,
            OperationCanceledException, CoreException, MisconfigurationException {
        if (!MarkOccurrencesPreferencesPage.useMarkOccurrences()) {
            return new PyMarkOccurrencesRequest(null, null, false);
        }
        PyEdit pyEdit = (PyEdit) baseEditor;

        //ok, the editor is still there wit ha document... move on
        PyRefactorAction pyRefactorAction = getRefactorAction(pyEdit);

        final RefactoringRequest req = getRefactoringRequest(pyEdit, pyRefactorAction,
                PySelection.fromTextSelection(this.ps));

        if (req == null || !req.nature.getRelatedInterpreterManager().isConfigured()) { //we check if it's configured because it may still be a stub...
            return new PyMarkOccurrencesRequest(null, null, false);
        }

        PyRenameEntryPoint processor = new PyRenameEntryPoint(req);
        //to see if a new request was not created in the meantime (in which case this one will be cancelled)
        if (monitor.isCanceled()) {
            return new PyMarkOccurrencesRequest(null, null, false);
        }

        try {
            processor.checkInitialConditions(monitor);
            if (monitor.isCanceled()) {
                return new PyMarkOccurrencesRequest(null, null, false);
            }

            processor.checkFinalConditions(monitor, null);
            if (monitor.isCanceled()) {
                return new PyMarkOccurrencesRequest(null, null, false);
            }

            //ok, pre-conditions suceeded
            return new PyMarkOccurrencesRequest(req, processor, true);
        } catch (Throwable e) {
            throw new RuntimeException("Error in occurrences while analyzing modName:" + req.moduleName
                    + " initialName:" + req.initialName + " line (start at 0):" + req.ps.getCursorLine(), e);
        }
    }

    /**
     * @param markOccurrencesRequest 
     * @return true if the annotations were removed and added without any problems and false otherwise
     */
    @Override
    protected synchronized Map<Annotation, Position> getAnnotationsToAddAsMap(final BaseEditor baseEditor,
            IAnnotationModel annotationModel, MarkOccurrencesRequest markOccurrencesRequest, IProgressMonitor monitor)
            throws BadLocationException {
        PyEdit pyEdit = (PyEdit) baseEditor;
        PySourceViewer viewer = pyEdit.getPySourceViewer();
        if (viewer == null || monitor.isCanceled()) {
            return null;
        }
        if (viewer.getIsInToggleCompletionStyle() || monitor.isCanceled()) {
            return null;
        }

        PyMarkOccurrencesRequest pyMarkOccurrencesRequest = (PyMarkOccurrencesRequest) markOccurrencesRequest;
        RefactoringRequest req = pyMarkOccurrencesRequest.refactoringRequest;
        PyRenameEntryPoint processor = pyMarkOccurrencesRequest.pyRenameEntryPoint;
        HashSet<ASTEntry> occurrences = processor.getOccurrences();
        if (occurrences == null) {
            if (DEBUG) {
                System.out.println("Occurrences == null");
            }
            return null;
        }

        IDocument doc = pyEdit.getDocument();
        Map<Annotation, Position> toAddAsMap = new HashMap<Annotation, Position>();
        boolean markOccurrencesInStrings = MarkOccurrencesPreferencesPage.useMarkOccurrencesInStrings();

        //get the annotations to add
        for (ASTEntry entry : occurrences) {
            if (!markOccurrencesInStrings) {
                if (entry.node instanceof Name) {
                    Name name = (Name) entry.node;
                    if (name.ctx == Name.Artificial) {
                        continue;
                    }
                }
            }

            SimpleNode node = entry.getNameNode();
            IRegion lineInformation = doc.getLineInformation(node.beginLine - 1);

            try {
                Annotation annotation = new Annotation(getOccurrenceAnnotationsType(), false, "occurrence");
                Position position = new Position(lineInformation.getOffset() + node.beginColumn - 1,
                        req.initialName.length());
                toAddAsMap.put(annotation, position);

            } catch (Exception e) {
                Log.log(e);
            }
        }
        return toAddAsMap;
    }

    /**
     * @param pyEdit the editor where we should look for the occurrences
     * @param pyRefactorAction the action that will return the initial refactoring request
     * @param ps the pyselection used (if null it will be created in this method)
     * @return a refactoring request suitable for finding the locals in the file
     * @throws BadLocationException
     * @throws MisconfigurationException 
     */
    public static RefactoringRequest getRefactoringRequest(final PyEdit pyEdit, PyRefactorAction pyRefactorAction,
            PySelection ps) throws BadLocationException, MisconfigurationException {
        final RefactoringRequest req = pyRefactorAction.getRefactoringRequest();
        req.ps = ps;
        req.fillInitialNameAndOffset();
        req.inputName = "foo";
        req.setAdditionalInfo(RefactoringRequest.FIND_DEFINITION_IN_ADDITIONAL_INFO, false);
        req.setAdditionalInfo(RefactoringRequest.FIND_REFERENCES_ONLY_IN_LOCAL_SCOPE, true);
        return req;
    }

    /**
     * @param pyEdit the editor that will have this action
     * @return the action (with the pyedit attached to it)
     */
    public static PyRefactorAction getRefactorAction(PyEdit pyEdit) {
        PyRefactorAction pyRefactorAction = new PyRefactorAction() {

            @Override
            protected String perform(IAction action, IProgressMonitor monitor) throws Exception {
                throw new RuntimeException("Perform should not be called in this case.");
            }
        };
        pyRefactorAction.setEditor(pyEdit);
        return pyRefactorAction;
    }

    private static final String ANNOTATIONS_CACHE_KEY = "MarkOccurrencesJob Annotations";
    private static final String OCCURRENCE_ANNOTATION_TYPE = "com.python.pydev.occurrences";

    @Override
    protected String getOccurrenceAnnotationsCacheKey() {
        return ANNOTATIONS_CACHE_KEY;
    }

    @Override
    protected String getOccurrenceAnnotationsType() {
        return OCCURRENCE_ANNOTATION_TYPE;
    }

    /**
     * This is the function that should be called when we want to schedule a request for 
     * a mark occurrences job.
     */
    public static synchronized void scheduleRequest(WeakReference<BaseEditor> editor2, TextSelectionUtils ps) {
        BaseMarkOccurrencesJob.scheduleRequest(new MarkOccurrencesJob(editor2, ps));
    }

}
