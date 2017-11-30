/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.psi.impl.source.tree.injected;

import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.impl.DocumentCommitThread;
import com.intellij.psi.impl.PsiDocumentManagerBase;
import com.intellij.psi.impl.smartPointers.SmartPointerManagerImpl;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.resolve.FileContextUtil;
import com.intellij.psi.impl.source.text.BlockSupportImpl;
import com.intellij.psi.impl.source.text.DiffLog;
import com.intellij.psi.impl.source.tree.FileElement;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.impl.source.tree.TreeElement;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.injection.ReferenceInjector;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.PathUtil;
import com.intellij.util.SmartList;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class InjectionRegistrarImpl extends MultiHostRegistrarImpl implements MultiHostRegistrar, ModificationTracker {
  private List<Pair<Place, PsiFile>> result;
  private Language myLanguage;
  private List<LiteralTextEscaper<? extends PsiLanguageInjectionHost>> escapers;
  private List<PsiLanguageInjectionHost.Shred> shreds;
  private StringBuilder outChars;
  private boolean isOneLineEditor;
  private boolean cleared;
  private String fileExtension;
  private final Project myProject;
  private final PsiManager myPsiManager;
  private final DocumentEx myHostDocument;
  private final VirtualFile myHostVirtualFile;
  private final PsiElement myContextElement;
  private final PsiFile myHostPsiFile;
  private ReferenceInjector myReferenceInjector;

  InjectionRegistrarImpl(@NotNull Project project, @NotNull PsiFile hostPsiFile, @NotNull PsiElement contextElement) {
    myProject = project;
    myContextElement = contextElement;
    myHostPsiFile = PsiUtilCore.getTemplateLanguageFile(hostPsiFile);
    myPsiManager = myHostPsiFile.getManager();
    cleared = true;
    FileViewProvider viewProvider = myHostPsiFile.getViewProvider();
    myHostVirtualFile = viewProvider.getVirtualFile();
    myHostDocument = (DocumentEx)viewProvider.getDocument();
  }

  @Nullable("null means nobody cared to call .doneInjecting()")
  public List<Pair<Place, PsiFile>> getResult() {
    return result;
  }

  @NotNull
  @Override
  public MultiHostRegistrar startInjecting(@NotNull Language language) {
    return startInjecting(language, null);
  }

  @NotNull
  @Override
  public MultiHostRegistrar startInjecting(@NotNull Language language, @Nullable String extension) {
    fileExtension = extension;
    escapers = new SmartList<>();
    shreds = new SmartList<>();
    outChars = new StringBuilder();

    if (!cleared) {
      clear();
      throw new IllegalStateException("Seems you haven't called doneInjecting()");
    }

    if (LanguageParserDefinitions.INSTANCE.forLanguage(language) == null) {
      throw new UnsupportedOperationException("Cannot inject language '" + language + "' because it has no ParserDefinition");
    }
    myLanguage = language;
    return this;
  }

  private void clear() {
    escapers.clear();
    shreds.clear();
    outChars.setLength(0);
    isOneLineEditor = false;
    fileExtension = null;
    myLanguage = null;

    cleared = true;
  }

  @Override
  @NotNull
  public MultiHostRegistrar addPlace(@NonNls @Nullable String prefix,
                                     @NonNls @Nullable String suffix,
                                     @NotNull PsiLanguageInjectionHost host,
                                     @NotNull TextRange rangeInsideHost) {

    PsiFile containingFile = PsiUtilCore.getTemplateLanguageFile(host);
    assert containingFile == myHostPsiFile : exceptionContext("Trying to inject into foreign file: "+containingFile);
    TextRange hostTextRange = host.getTextRange();
    if (!hostTextRange.contains(rangeInsideHost.shiftRight(hostTextRange.getStartOffset()))) {
      clear();
      throw new IllegalArgumentException("rangeInsideHost must lie within host text range. rangeInsideHost:"+rangeInsideHost+"; host textRange:"+
                                         hostTextRange);
    }
    if (myLanguage == null && myReferenceInjector == null) {
      clear();
      throw new IllegalStateException("Seems you haven't called startInjecting()");
    }

    if (prefix == null) prefix = "";
    if (suffix == null) suffix = "";
    cleared = false;
    int startOffset = outChars.length();
    outChars.append(prefix);
    LiteralTextEscaper<? extends PsiLanguageInjectionHost> textEscaper = host.createLiteralTextEscaper();
    escapers.add(textEscaper);
    isOneLineEditor |= textEscaper.isOneLine();
    TextRange relevantRange = textEscaper.getRelevantTextRange().intersection(rangeInsideHost);
    if (relevantRange == null) {
      relevantRange = TextRange.from(textEscaper.getRelevantTextRange().getStartOffset(), 0);
    }
    else {
      int before = outChars.length();
      boolean result = textEscaper.decode(relevantRange, outChars);
      int after = outChars.length();
      assert after >= before : "Escaper " + textEscaper + "("+textEscaper.getClass()+") must not mangle char buffer";
      if (!result) {
        // if there are invalid chars, adjust the range
        int offsetInHost = textEscaper.getOffsetInHost(outChars.length() - before, rangeInsideHost);
        relevantRange = relevantRange.intersection(new ProperTextRange(0, offsetInHost));
      }
    }
    outChars.append(suffix);
    int endOffset = outChars.length();
    TextRange relevantRangeInHost = relevantRange.shiftRight(hostTextRange.getStartOffset());
    SmartPointerManagerImpl manager = (SmartPointerManagerImpl)SmartPointerManager.getInstance(myProject);
    shreds.add(new ShredImpl(manager.createSmartPsiFileRangePointer(myHostPsiFile, relevantRangeInHost, true),
                             manager.createSmartPsiElementPointer(host, myHostPsiFile, true),
                             prefix, suffix, new ProperTextRange(startOffset, endOffset), false));
    return this;
  }

  @Override
  public void doneInjecting() {
    try {
      if (shreds.isEmpty()) {
        throw new IllegalStateException("Seems you haven't called addPlace()");
      }
      if (myReferenceInjector != null) {
        addToResults(new Place(shreds), null);
        return;
      }
      PsiDocumentManagerBase documentManager = (PsiDocumentManagerBase)PsiDocumentManager.getInstance(myProject);

      Place place = new Place(shreds);
      DocumentWindowImpl documentWindow = new DocumentWindowImpl(myHostDocument, isOneLineEditor, place);
      String fileName = PathUtil.makeFileName(myHostVirtualFile.getName(), fileExtension);
      VirtualFileWindowImpl virtualFile = new VirtualFileWindowImpl(fileName, myHostVirtualFile, documentWindow, myLanguage, outChars);
      Language forcedLanguage = myContextElement.getUserData(InjectedFileViewProvider.LANGUAGE_FOR_INJECTED_COPY_KEY);
      myLanguage = forcedLanguage == null ? LanguageSubstitutors.INSTANCE.substituteLanguage(myLanguage, virtualFile, myProject) : forcedLanguage;

      createDocument(virtualFile);

      InjectedFileViewProvider viewProvider = new InjectedFileViewProvider(myPsiManager, virtualFile, documentWindow, myLanguage);
      ParserDefinition parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(myLanguage);
      assert parserDefinition != null : "Parser definition for language "+myLanguage+" is null";
      PsiFile psiFile = parserDefinition.createFile(viewProvider);

      SmartPsiElementPointer<PsiLanguageInjectionHost> pointer = ((ShredImpl)shreds.get(0)).getSmartPointer();

      synchronized (InjectedLanguageManagerImpl.ourInjectionPsiLock) {
        final ASTNode parsedNode = keepTreeFromChameleoningBack(psiFile);

        assert parsedNode instanceof FileElement : "Parsed to "+parsedNode+" instead of FileElement";

        String documentText = documentManager.getLastCommittedDocument(documentWindow).getText();
        assert ((FileElement)parsedNode).textMatches(outChars) : exceptionContext("Before patch: doc:\n'" + documentText + "'\n---PSI:\n'" + parsedNode.getText() + "'\n---chars:\n'"+outChars+"'");

        viewProvider.setPatchingLeaves(true);
        try {
          patchLeaves(parsedNode, escapers, place);
        }
        catch (ProcessCanceledException e) {
          throw e;
        }
        catch (RuntimeException e) {
          throw new RuntimeException(exceptionContext("Patch error"), e);
        }
        finally {
          viewProvider.setPatchingLeaves(false);
        }
        if (!((FileElement)parsedNode).textMatches(documentText)) {
          throw new AssertionError(exceptionContext("After patch: doc:\n'" + documentText + "'\n---PSI:\n'" + parsedNode.getText() + "'\n---chars:\n'" + outChars + "'"));
        }

        virtualFile.setContent(null, documentWindow.getText(), false);
        virtualFile.setWritable(virtualFile.getDelegate().isWritable());

        cacheEverything(place, documentWindow, viewProvider, psiFile, pointer);

        PsiFile cachedPsiFile = documentManager.getCachedPsiFile(documentWindow);
        assert cachedPsiFile == psiFile : "Cached psi :"+ cachedPsiFile +" instead of "+psiFile;

        assert place.isValid();
        assert viewProvider.isValid();
        PsiFile newFile = registerDocument(documentWindow, psiFile, place, myHostPsiFile, documentManager);
        boolean mergeHappened = newFile != psiFile;
        if (mergeHappened) {
          InjectedLanguageUtil.clearCaches(psiFile, documentWindow);
          psiFile = newFile;
          viewProvider = (InjectedFileViewProvider)psiFile.getViewProvider();
          documentWindow = (DocumentWindowImpl)viewProvider.getDocument();
          virtualFile = (VirtualFileWindowImpl)viewProvider.getVirtualFile();
          boolean shredsRewritten = cacheEverything(place, documentWindow, viewProvider, psiFile, pointer);
          if (!shredsRewritten) {
            place.dispose();
            place = documentWindow.getShreds();
          }
        }

        assert psiFile.isValid();
        assert place.isValid();
        assert viewProvider.isValid();

        try {
          List<Trinity<IElementType, SmartPsiElementPointer<PsiLanguageInjectionHost>, TextRange>> tokens = obtainHighlightTokensFromLexer(myLanguage, outChars, escapers, place, virtualFile, myProject);
          psiFile.putUserData(InjectedLanguageUtil.HIGHLIGHT_TOKENS, tokens);
        }
        catch (ProcessCanceledException e) {
          throw e;
        }
        catch (RuntimeException e) {
          throw new RuntimeException(exceptionContext("Obtaining tokens error"), e);
        }

        addToResults(place, psiFile);

        assertEverythingIsAllright(documentManager, documentWindow, psiFile);
      }
    }
    finally {
      clear();
    }
  }

  public void injectReference(@NotNull Language language,
                              @NotNull String prefix,
                              @NotNull String suffix,
                              @NotNull PsiLanguageInjectionHost host,
                              @NotNull TextRange rangeInsideHost) {
    ParserDefinition parser = LanguageParserDefinitions.INSTANCE.forLanguage(language);
    if (parser != null) {
      throw new IllegalArgumentException("Language "+language+" being injected as reference must not have ParserDefinition and yet - "+parser);
    }
    ReferenceInjector injector = ReferenceInjector.findById(language.getID());
    if (injector == null) {
      throw new IllegalArgumentException("Language "+language+" being injected as reference must register reference injector");
    }
    escapers = new SmartList<>();
    shreds = new SmartList<>();
    outChars = new StringBuilder();

    if (!cleared) {
      clear();
      throw new IllegalStateException("Seems you haven't called doneInjecting()");
    }

    myReferenceInjector = injector;
    myLanguage = language;

    addPlace(prefix, suffix, host, rangeInsideHost);
    doneInjecting();
  }

  private static void createDocument(@NotNull LightVirtualFile virtualFile) {
    CharSequence content = virtualFile.getContent();
    DocumentImpl document = new DocumentImpl(content, StringUtil.indexOf(content, '\r') >= 0, false);
    FileDocumentManagerImpl.registerDocument(document, virtualFile);
  }

  // returns true if shreds were set, false if old ones were reused
  private static boolean cacheEverything(@NotNull Place place,
                                         @NotNull DocumentWindowImpl documentWindow,
                                         @NotNull InjectedFileViewProvider viewProvider,
                                         @NotNull PsiFile psiFile,
                                         @NotNull SmartPsiElementPointer<PsiLanguageInjectionHost> pointer) {
    FileDocumentManagerImpl.registerDocument(documentWindow, viewProvider.getVirtualFile());

    DebugUtil.startPsiModification("MultiHostRegistrar cacheEverything");
    try {
      viewProvider.forceCachedPsi(psiFile);
    }
    finally {
      DebugUtil.finishPsiModification();
    }

    psiFile.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, pointer);
    ((PsiDocumentManagerBase)PsiDocumentManager.getInstance(psiFile.getProject())).associatePsi(documentWindow, psiFile);

    keepTreeFromChameleoningBack(psiFile);

    return viewProvider.setShreds(place, psiFile.getProject());
  }


  @NonNls
  private String exceptionContext(@NonNls String msg) {
    return msg + ".\n" +
           myLanguage+";\n "+
           "Host file: "+myHostPsiFile+" in '" + myHostVirtualFile.getPresentableUrl() + "'" +
           (PsiDocumentManager.getInstance(myProject).isUncommited(myHostDocument) ? " (uncommitted)": "")+ "\n" +
           "Context element "+myContextElement.getTextRange() + ": '" + myContextElement +"'; "+
           "Ranges: "+shreds;
  }

  private static final Key<ASTNode> TREE_HARD_REF = Key.create("TREE_HARD_REF");
  private static ASTNode keepTreeFromChameleoningBack(PsiFile psiFile) {
    // need to keep tree reachable to avoid being garbage-collected (via WeakReference in PsiFileImpl)
    // and then being reparsed from wrong (escaped) document content
    ASTNode node = psiFile.getNode();
    // expand chameleons
    ASTNode child = node.getFirstChildNode();

    assert !TreeUtil.isCollapsedChameleon(node) : "Chameleon "+node+" is collapsed; file: "+psiFile+"; language: "+psiFile.getLanguage();
    psiFile.putUserData(TREE_HARD_REF, node);

    // just to use child variable
    if (child == null) {
      assert node != null;
    }
    return node;
  }

  private void assertEverythingIsAllright(PsiDocumentManagerBase documentManager, DocumentWindowImpl documentWindow, PsiFile psiFile) {
    boolean isAncestor = false;
    for (PsiLanguageInjectionHost.Shred shred : shreds) {
      PsiLanguageInjectionHost host = shred.getHost();
      isAncestor |= PsiTreeUtil.isAncestor(myContextElement, host, false);
    }
    assert isAncestor : exceptionContext(myContextElement + " must be the parent of at least one of injection hosts");

    InjectedFileViewProvider injectedFileViewProvider = (InjectedFileViewProvider)psiFile.getViewProvider();
    assert injectedFileViewProvider.isValid() : "Invalid view provider: "+injectedFileViewProvider;
    DocumentEx frozenWindow = documentManager.getLastCommittedDocument(documentWindow);
    assert psiFile.textMatches(frozenWindow.getText()) : "Document window text mismatch";
    assert injectedFileViewProvider.getDocument() == documentWindow : "Provider document mismatch";
    assert documentManager.getCachedDocument(psiFile) == documentWindow : "Cached document mismatch";
    assert Comparing.equal(psiFile.getVirtualFile(), injectedFileViewProvider.getVirtualFile()) : "Virtual file mismatch: " +
                                                                                                  psiFile.getVirtualFile() +
                                                                                                  "; " +
                                                                                                  injectedFileViewProvider.getVirtualFile();
    PsiDocumentManagerBase.checkConsistency(psiFile, frozenWindow);
  }

  void addToResults(@NotNull Place place, @Nullable /*WAT?*/PsiFile psiFile, @NotNull InjectionRegistrarImpl from) {
    addToResults(place, psiFile);
    myReferenceInjector = from.myReferenceInjector;
  }

  private void addToResults(@NotNull Place place, @Nullable /*WAT?*/PsiFile psiFile) {
    if (result == null) {
      result = new SmartList<>();
    }
    result.add(Pair.create(place, psiFile));
  }


  private static void patchLeaves(@NotNull ASTNode parsedNode,
                                  @NotNull List<LiteralTextEscaper<? extends PsiLanguageInjectionHost>> escapers,
                                  @NotNull Place shreds) {
    LeafPatcher patcher = new LeafPatcher(shreds, escapers);
    ((TreeElement)parsedNode).acceptTree(patcher);

    assert ((TreeElement)parsedNode).textMatches(patcher.catLeafs) : "Malformed PSI structure: leaf texts do not add up to the whole file text." +
                                                  "\nFile text (from tree)  :'"+parsedNode.getText()+"'" +
                                                  "\nFile text (from PSI)   :'"+parsedNode.getPsi().getText()+"'" +
                                                  "\nLeaf texts concatenated:'"+ patcher.catLeafs +"';" +
                                                  "\nFile root: "+parsedNode+
                                                  "\nLanguage: "+parsedNode.getPsi().getLanguage()+
                                                  "\nHost file: "+ shreds.getHostPointer().getVirtualFile()
        ;
    DebugUtil.startPsiModification("injection leaf patching");
    try {
      for (Map.Entry<LeafElement, String> entry : patcher.newTexts.entrySet()) {
        LeafElement leaf = entry.getKey();
        String newText = entry.getValue();
        leaf.rawReplaceWithText(newText);
      }
    }
    finally {
      DebugUtil.finishPsiModification();
    }

    TreeUtil.clearCaches((TreeElement)parsedNode);
  }

  // under InjectedLanguageManagerImpl.ourInjectionPsiLock
  @NotNull
  private static PsiFile registerDocument(@NotNull DocumentWindowImpl newDocumentWindow,
                                          @NotNull PsiFile newInjectedPsi,
                                          @NotNull Place shreds,
                                          @NotNull PsiFile hostPsiFile,
                                          @NotNull PsiDocumentManager documentManager) {
    List<DocumentWindow> injected = InjectedLanguageUtil.getCachedInjectedDocuments(hostPsiFile);

    for (int i = injected.size()-1; i>=0; i--) {
      DocumentWindowImpl oldDocument = (DocumentWindowImpl)injected.get(i);
      final PsiFileImpl oldFile = (PsiFileImpl)documentManager.getCachedPsiFile(oldDocument);
      FileViewProvider viewProvider;
      if (oldFile == null ||
          !oldFile.isValid() ||
          !((viewProvider = oldFile.getViewProvider()) instanceof InjectedFileViewProvider) ||
          ((InjectedFileViewProvider)viewProvider).isDisposed()
        ) {
        injected.remove(i);
        Disposer.dispose(oldDocument);
        continue;
      }

      final ASTNode newInjectedNode = newInjectedPsi.getNode();
      final ASTNode oldFileNode = oldFile.getNode();
      assert newInjectedNode != null : "New node is null";
      if (oldDocument.areRangesEqual(newDocumentWindow)) {
        if (oldFile.getFileType() != newInjectedPsi.getFileType() || oldFile.getLanguage() != newInjectedPsi.getLanguage()) {
          injected.remove(i);
          Disposer.dispose(oldDocument);
          continue;
        }
        oldFile.putUserData(FileContextUtil.INJECTED_IN_ELEMENT, newInjectedPsi.getUserData(FileContextUtil.INJECTED_IN_ELEMENT));

        assert shreds.isValid();
        mergePsi(oldFile, oldFileNode, newInjectedPsi, newInjectedNode);
        assert shreds.isValid();

        return oldFile;
      }
      else if (intersect(oldDocument, newDocumentWindow)) {
        injected.remove(i); // injected fragments should not overlap. In the End, there can be only one.
      }
    }
    injected.add(newDocumentWindow);

    return newInjectedPsi;
  }

  private static void mergePsi(@NotNull PsiFileImpl oldFile,
                               @NotNull ASTNode oldFileNode,
                               @NotNull PsiFile injectedPsi,
                               @NotNull ASTNode injectedNode) {
    if (!oldFile.textMatches(injectedPsi)) {
      InjectedFileViewProvider oldViewProvider = (InjectedFileViewProvider)oldFile.getViewProvider();
      oldViewProvider.performNonPhysically(() -> {
        DebugUtil.startPsiModification("injected tree diff");
        try {
          final DiffLog diffLog = BlockSupportImpl.mergeTrees(oldFile, oldFileNode, injectedNode, new DaemonProgressIndicator(),
                                                              oldFileNode.getText());
          DocumentCommitThread.doActualPsiChange(oldFile, diffLog);
        }
        finally {
          DebugUtil.finishPsiModification();
        }
      });
    }
  }

  private static boolean intersect(DocumentWindowImpl doc1, DocumentWindowImpl doc2) {
    Segment[] hostRanges1 = doc1.getHostRanges();
    Segment[] hostRanges2 = doc2.getHostRanges();
    // DocumentWindowImpl.getHostRanges() may theoretically return non-sorted ranges
    for (Segment segment1 : hostRanges1) {
      for (Segment segment2 : hostRanges2) {
        if (Math.max(segment1.getStartOffset(), segment2.getStartOffset()) < Math.min(segment1.getEndOffset(), segment2.getEndOffset())) {
          return true;
        }
      }
    }
    return false;
  }

  // returns lexer element types with corresponding ranges in encoded (injection host based) PSI
  private static List<Trinity<IElementType, SmartPsiElementPointer<PsiLanguageInjectionHost>, TextRange>>
          obtainHighlightTokensFromLexer(Language language,
                                         StringBuilder outChars,
                                         List<LiteralTextEscaper<? extends PsiLanguageInjectionHost>> escapers,
                                         Place shreds,
                                         VirtualFileWindow virtualFile,
                                         Project project) {
    SyntaxHighlighter syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, (VirtualFile)virtualFile);
    Lexer lexer = syntaxHighlighter.getHighlightingLexer();
    lexer.start(outChars);
    int hostNum = -1;
    int prevHostEndOffset = 0;
    SmartPsiElementPointer<PsiLanguageInjectionHost> host = null;
    LiteralTextEscaper<? extends PsiLanguageInjectionHost> escaper = null;
    int prefixLength = 0;
    int suffixLength = 0;
    TextRange rangeInsideHost = null;
    int shredEndOffset = -1;
    List<Trinity<IElementType, SmartPsiElementPointer<PsiLanguageInjectionHost>, TextRange>> tokens = new ArrayList<>(10);
    for (IElementType tokenType = lexer.getTokenType(); tokenType != null; lexer.advance(), tokenType = lexer.getTokenType()) {
      TextRange range = new ProperTextRange(lexer.getTokenStart(), lexer.getTokenEnd());
      while (range != null && !range.isEmpty()) {
        if (range.getStartOffset() >= shredEndOffset) {
          hostNum++;
          PsiLanguageInjectionHost.Shred shred = shreds.get(hostNum);
          shredEndOffset = shred.getRange().getEndOffset();
          prevHostEndOffset = range.getStartOffset();
          host = ((ShredImpl)shred).getSmartPointer();
          escaper = escapers.get(hostNum);
          rangeInsideHost = shred.getRangeInsideHost();
          prefixLength = shred.getPrefix().length();
          suffixLength = shred.getSuffix().length();
        }
        //in prefix/suffix or spills over to next fragment
        if (range.getStartOffset() < prevHostEndOffset + prefixLength) {
          range = new UnfairTextRange(prevHostEndOffset + prefixLength, range.getEndOffset());
        }
        TextRange spilled = null;
        if (range.getEndOffset() > shredEndOffset - suffixLength) {
          spilled = new UnfairTextRange(shredEndOffset, range.getEndOffset());
          range = new UnfairTextRange(range.getStartOffset(), shredEndOffset-suffixLength);
        }
        if (!range.isEmpty()) {
          int start = escaper.getOffsetInHost(range.getStartOffset() - prevHostEndOffset - prefixLength, rangeInsideHost);
          if (start == -1) start = rangeInsideHost.getStartOffset();
          int end = escaper.getOffsetInHost(range.getEndOffset() - prevHostEndOffset - prefixLength, rangeInsideHost);
          if (end == -1) {
            end = rangeInsideHost.getEndOffset();
            prevHostEndOffset = shredEndOffset;
          }
          TextRange rangeInHost = new ProperTextRange(start, end);
          tokens.add(Trinity.create(tokenType, host, rangeInHost));
        }
        range = spilled;
      }
    }
    return tokens;
  }

  // for CachedValue
  @Override
  public long getModificationCount() {
    List<PsiLanguageInjectionHost.Shred> shredList = shreds;
    if (shredList != null) {
      for (PsiLanguageInjectionHost.Shred shred : shredList) {
        if (!shred.isValid()) return -1;
      }
    }
    DocumentEx hostDocument = myHostDocument;
    return hostDocument == null ? -1 : hostDocument.getModificationStamp();
  }

  @Override
  public String toString() {
    return String.valueOf(result);
  }

  // performance: avoid context.getContainingFile()
  @NotNull
  PsiFile getHostPsiFile() {
    return myHostPsiFile;
  }

  ReferenceInjector getReferenceInjector() {
    return myReferenceInjector;
  }

  boolean isValid() {
    List<Pair<Place, PsiFile>> result = getResult();
    if (result == null) return false;
    for (Pair<Place, PsiFile> pair : result) {
      if (!pair.getFirst().isValid()) return false;
    }
    return true;
  }
}
