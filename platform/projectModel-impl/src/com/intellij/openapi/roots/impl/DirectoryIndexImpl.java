/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.openapi.roots.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.util.*;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.containers.Stack;
import gnu.trove.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.*;

public class DirectoryIndexImpl extends DirectoryIndex {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.roots.impl.DirectoryIndexImpl");
  private static final boolean CHECK = ApplicationManager.getApplication().isUnitTestMode();

  protected final Project myProject;
  protected final DirectoryIndexExcludePolicy[] myExcludePolicies;
  protected volatile IndexState myState;

  private volatile boolean myInitialized = false;
  private volatile boolean myDisposed = false;
  private final PackageSink mySink = new PackageSink();

  public DirectoryIndexImpl(@NotNull Project project) {
    myProject = project;
    myExcludePolicies = Extensions.getExtensions(DirectoryIndexExcludePolicy.EP_NAME, myProject);
    myState = new IndexState();
    Disposer.register(project, new Disposable() {
      @Override
      public void dispose() {
        myDisposed = true;
      }
    });
  }

  private class PackageSink extends QueryFactory<VirtualFile, Pair<IndexState, List<VirtualFile>>> {
    private final Condition<VirtualFile> IS_VALID = new Condition<VirtualFile>() {
      @Override
      public boolean value(final VirtualFile virtualFile) {
        return virtualFile.isValid();
      }
    };

    private PackageSink() {
      registerExecutor(new QueryExecutor<VirtualFile, Pair<IndexState, List<VirtualFile>>>() {
        @Override
        public boolean execute(@NotNull final Pair<IndexState, List<VirtualFile>> stateAndDirs,
                               @NotNull final Processor<VirtualFile> consumer) {
          for (VirtualFile dir : stateAndDirs.second) {
            DirectoryInfo info = stateAndDirs.first.myDirToInfoMap.get(getId(dir));
            assert info != null;

            if (!info.isInLibrarySource() || info.isInModuleSource() || info.hasLibraryClassRoot()) {
              if (!consumer.process(dir)) return false;
            }
          }
          return true;
        }
      });
    }

    public Query<VirtualFile> search(@NotNull String packageName, boolean includeLibrarySources) {
      checkAvailability();
      dispatchPendingEvents();

      IndexState state = myState;
      int[] allDirs = state.getDirsForPackage(packageName);
      if (allDirs == null) allDirs = ArrayUtil.EMPTY_INT_ARRAY;

      List<VirtualFile> files = new ArrayList<VirtualFile>(allDirs.length);
      for (int dir : allDirs) {
        VirtualFile file = findFileById(dir);
        if (file != null) {
          files.add(file);
        }
      }

      Query<VirtualFile> query = includeLibrarySources ? new CollectionQuery<VirtualFile>(files) : createQuery(Pair.create(state, files));
      return new FilteredQuery<VirtualFile>(query, IS_VALID);
    }
  }

  @Override
  @NotNull
  public Query<VirtualFile> getDirectoriesByPackageName(@NotNull String packageName, boolean includeLibrarySources) {
    return mySink.search(packageName, includeLibrarySources);
  }

  private static class FileSystemPersistenceHolder {
    private static final FileSystemPersistence persistence = ApplicationManager.getApplication().getComponents(FileSystemPersistence.class)[0];
  }

  @Override
  @TestOnly
  public void checkConsistency() {
    doCheckConsistency(false);
    doCheckConsistency(true);
  }

  @TestOnly
  public void assertAncestorConsistent() {
    myState.assertAncestorsConsistent();
  }

  @TestOnly
  private void doCheckConsistency(boolean reverseAllSets) {
    assert myInitialized;
    assert !myDisposed;

    final IndexState oldState = myState;
    myState.assertAncestorsConsistent();
    myState = myState.copy(null);

    myState.doInitialize(reverseAllSets);

    int[] keySet = myState.myDirToInfoMap.keys();
    assert keySet.length == oldState.myDirToInfoMap.keys().length;
    for (int file : keySet) {
      DirectoryInfo info1 = myState.getInfo(file);
      DirectoryInfo info2 = oldState.getInfo(file);
      assert info1.equals(info2);
      info1.assertConsistency();
    }

    assert myState.myPackageNameToDirsMap.size() == oldState.myPackageNameToDirsMap.size();
    myState.myPackageNameToDirsMap.forEachEntry(new TObjectIntProcedure<String>() {
      @Override
      public boolean execute(String packageName, int i) {
        int[] dirs = oldState.getDirsForPackage(packageName);
        int[] dirs1 = myState.getDirsForPackage(packageName);

        TIntHashSet set1 = new TIntHashSet(dirs);
        TIntHashSet set2 = new TIntHashSet(dirs1);
        assert set1.equals(set2);
        return true;
      }
    });
  }

  @Override
  public boolean isInitialized() {
    return myInitialized;
  }

  public void initialize() {
    if (myInitialized) {
      LOG.error("Directory index is already initialized.");
      return;
    }

    if (myDisposed) {
      LOG.error("Directory index is already disposed for this project");
      return;
    }

    myInitialized = true;
    long l = System.currentTimeMillis();
    doInitialize();
    LOG.info("Directory index initialized in " + (System.currentTimeMillis() - l) + " ms, indexed " + myState.myDirToInfoMap.size() + " directories");
  }

  protected void doInitialize() {
    IndexState newState = new IndexState();
    newState.doInitialize(false);
    myState = newState;
  }

  private boolean isExcludeRootForModule(@NotNull Module module, VirtualFile excludeRoot) {
    for (DirectoryIndexExcludePolicy policy : myExcludePolicies) {
      if (policy.isExcludeRootForModule(module, excludeRoot)) return true;
    }
    return false;
  }

  @NotNull
  protected static ContentEntry[] getContentEntries(@NotNull Module module) {
    return ModuleRootManager.getInstance(module).getContentEntries();
  }

  @NotNull
  private static OrderEntry[] getOrderEntries(@NotNull Module module) {
    return ModuleRootManager.getInstance(module).getOrderEntries();
  }

  private static boolean isIgnored(@NotNull VirtualFile f) {
    return FileTypeRegistry.getInstance().isFileIgnored(f);
  }

  @Override
  public DirectoryInfo getInfoForDirectory(@NotNull VirtualFile dir) {
    checkAvailability();
    dispatchPendingEvents();

    if (!(dir instanceof VirtualFileWithId)) return null;
    return myState.getInfo(getId(dir));
  }

  @Override
  public boolean isProjectExcludeRoot(@NotNull VirtualFile dir) {
    checkAvailability();
    return dir instanceof VirtualFileWithId && myState.myProjectExcludeRoots.contains(getId(dir));
  }

  private static VirtualFile findFileById(int dir) {
    return FileSystemPersistenceHolder.persistence.findFileById(dir);
  }
  
  @Override
  public String getPackageName(@NotNull VirtualFile dir) {
    checkAvailability();
    if (!(dir instanceof VirtualFileWithId)) return null;
    return myState.myDirToPackageName.get(getId(dir));
  }

  protected void dispatchPendingEvents() {
  }

  private void checkAvailability() {
    if (!myInitialized) {
      LOG.error("Directory index is not initialized yet for " + myProject);
    }

    if (myDisposed) {
      LOG.error("Directory index is already disposed for " + myProject);
    }
  }

  @Nullable
  protected static String getPackageNameForSubdir(String parentPackageName, String subdirName) {
    if (parentPackageName == null) return null;
    return parentPackageName.isEmpty() ? subdirName : parentPackageName + "." + subdirName;
  }

  class IndexState {
    final TIntObjectHashMap<Set<String>> myExcludeRootsMap = new TIntObjectHashMap<Set<String>>();
    final TIntHashSet myProjectExcludeRoots = new TIntHashSet();
    final TIntObjectHashMap<DirectoryInfo> myDirToInfoMap = new TIntObjectHashMap<DirectoryInfo>();
    final TObjectIntHashMap<String> myPackageNameToDirsMap = new TObjectIntHashMap<String>();
    final List<int[]> multiDirPackages = new ArrayList<int[]>(Arrays.asList(new int[]{-1}));
    final TIntObjectHashMap<String> myDirToPackageName = new TIntObjectHashMap<String>();

    private IndexState() {
    }

    @Nullable
    private int[] getDirsForPackage(@NotNull String packageName) {
      int i = myPackageNameToDirsMap.get(packageName);
      return i == 0 ? null : i > 0 ? new int[]{i} : multiDirPackages.get(-i);
    }

    private void removeDirFromPackage(@NotNull String packageName, int dirId) {
      int i = myPackageNameToDirsMap.get(packageName);
      int[] oldPackageDirs = i == 0 ? null : i > 0 ? new int[]{i} : multiDirPackages.get(-i);
      int index = ArrayUtil.find(oldPackageDirs, dirId);
      assert index != -1;
      oldPackageDirs = ArrayUtil.remove(oldPackageDirs, index);

      if (oldPackageDirs.length == 0) {
        myPackageNameToDirsMap.remove(packageName);
        if (i < 0) {
          multiDirPackages.set(-i, null);
        }
      }
      else {
        assert i < 0 : i;
        multiDirPackages.set(-i, oldPackageDirs);
      }
    }

    private void addDirToPackage(@NotNull String packageName, int dirId) {
      int i = myPackageNameToDirsMap.get(packageName);

      if (i < 0) {
        // add another dir to the list of existing dirs
        int[] ids = multiDirPackages.get(-i);
        int[] newIds = ArrayUtil.append(ids, dirId);
        multiDirPackages.set(-i, newIds);
      }
      else if (i > 0) {
        // two dirs instead of one
        int newIndex = multiDirPackages.size();
        multiDirPackages.add(new int[]{i, dirId});
        myPackageNameToDirsMap.put(packageName, -newIndex);
      }
      else {
        // create new dir mapping
        myPackageNameToDirsMap.put(packageName, dirId);
      }
    }

    @NotNull
    private DirectoryInfo getOrCreateDirInfo(int dirId) {
      DirectoryInfo info = getInfo(dirId);
      if (info == null) {
        info = DirectoryInfo.createNew();
        storeInfo(info, dirId);
      }
      return info;
    }

    @Nullable
    DirectoryInfo getInfo(int fileId) {
      return myDirToInfoMap.get(fileId);
    }


    private void storeInfo(@NotNull DirectoryInfo info, int id) {
      if (CHECK) {
        VirtualFile file = findFileById(id);
        VirtualFile contentRoot = info.getContentRoot();
        if (file != null && contentRoot != null) {
            assert VfsUtilCore.isAncestor(contentRoot, file, false) : "File: "+file+"; Content root: "+contentRoot;
        }
      }
      myDirToInfoMap.put(id, info);
    }

    void assertAncestorsConsistent() {
      if (CHECK) {
        myDirToInfoMap.forEachEntry(new TIntObjectProcedure<DirectoryInfo>() {
          @Override
          public boolean execute(int id, DirectoryInfo info) {
            VirtualFile file = findFileById(id);
            if (file == null) {
              return true;
            }
            VirtualFile contentRoot = info.getContentRoot();
            if (contentRoot != null) {
              assertAncestor(info, contentRoot, id);
            }
            VirtualFile sourceRoot = info.getSourceRoot();
            if (sourceRoot != null) {
              assertAncestor(info, sourceRoot, id);

              if (contentRoot != null) {
                assert VfsUtilCore.isAncestor(contentRoot, sourceRoot, false) : contentRoot + ";" + sourceRoot;
              }
            }
            return true;
          }
        });
      }
    }

    void fillMapWithModuleContent(@NotNull VirtualFile root,
                                  final Module module,
                                  final VirtualFile contentRoot,
                                  @Nullable final ProgressIndicator progress) {
      final int contentRootId = contentRoot == null ? 0 : getId(contentRoot);
      if (contentRoot != null) {
        assert VfsUtilCore.isAncestor(contentRoot, root, false) : "Root: "+root+"; contentRoot: "+contentRoot;
      }
      VfsUtilCore.visitChildrenRecursively(root, new DirectoryVisitor() {
        @Override
        protected DirectoryInfo updateInfo(@NotNull VirtualFile file) {
          if (progress != null) {
            progress.checkCanceled();
          }
          if (isExcluded(contentRootId, file)) return null;
          if (isIgnored(file)) return null;

          DirectoryInfo info = getOrCreateDirInfo(getId(file));

          if (info.getModule() != null) { // module contents overlap
            VirtualFile dir = file.getParent();
            DirectoryInfo parentInfo = dir == null ? null : getInfo(getId(dir));
            if (parentInfo == null || !info.getModule().equals(parentInfo.getModule())) return null;
          }

          return info;
        }

        @Override
        protected void afterChildrenVisited(@NotNull VirtualFile file, @NotNull DirectoryInfo info) {
          with(getId(file), info, module, contentRoot, null, null, 0, null);
        }
      });
    }

    @NotNull
    private DirectoryInfo with(int id,
                               @NotNull DirectoryInfo info,
                               Module module,
                               VirtualFile contentRoot,
                               VirtualFile sourceRoot,
                               VirtualFile libraryClassRoot,
                               @DirectoryInfo.SourceFlag int sourceFlag,
                               OrderEntry[] orderEntries) {
      if (contentRoot != null) {
        assertAncestor(info, contentRoot, id);
      }
      if (sourceRoot != null) {
        VirtualFile root = contentRoot == null ? info.getContentRoot() : contentRoot;
        if (root != null) {
          assertAncestor(info, root, getId(sourceRoot));
        }
      }
      DirectoryInfo newInfo = info.with(module, contentRoot, sourceRoot, libraryClassRoot, (byte)sourceFlag, orderEntries);
      storeInfo(newInfo, id);
      return newInfo;
    }

    private abstract class DirectoryVisitor extends VirtualFileVisitor {
      private final Stack<DirectoryInfo> myDirectoryInfoStack = new Stack<DirectoryInfo>();

      @Override
      public boolean visitFile(@NotNull VirtualFile file) {
        if (!file.isDirectory()) return false;
        DirectoryInfo info = updateInfo(file);
        if (info != null) {
          myDirectoryInfoStack.push(info);
          return true;
        }
        return false;
      }

      @Override
      public void afterChildrenVisited(@NotNull VirtualFile file) {
        afterChildrenVisited(file, myDirectoryInfoStack.pop());
      }

      @Nullable
      protected abstract DirectoryInfo updateInfo(@NotNull VirtualFile file);

      protected void afterChildrenVisited(@NotNull VirtualFile file, @NotNull DirectoryInfo info) {}
    }
    
    private boolean isExcluded(int root, @NotNull VirtualFile dir) {
      if (root == 0) return false;
      Set<String> excludes = myExcludeRootsMap.get(root);
      return excludes != null && excludes.contains(dir.getUrl());
    }

    private void initModuleContents(@NotNull Module module, boolean reverseAllSets, @NotNull ProgressIndicator progress) {
      progress.checkCanceled();
      progress.setText2(ProjectBundle.message("project.index.processing.module.content.progress", module.getName()));

      ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
      VirtualFile[] contentRoots = rootManager.getContentRoots();
      if (reverseAllSets) {
        contentRoots = ArrayUtil.reverseArray(contentRoots);
      }

      for (final VirtualFile contentRoot : contentRoots) {
        fillMapWithModuleContent(contentRoot, module, contentRoot, progress);
      }
    }

    private void initModuleSources(@NotNull Module module, boolean reverseAllSets, @NotNull ProgressIndicator progress) {
      progress.checkCanceled();
      progress.setText2(ProjectBundle.message("project.index.processing.module.sources.progress", module.getName()));

      ContentEntry[] contentEntries = getContentEntries(module);

      if (reverseAllSets) {
        contentEntries = ArrayUtil.reverseArray(contentEntries);
      }

      for (ContentEntry contentEntry : contentEntries) {
        VirtualFile contentRoot = contentEntry.getFile();
        SourceFolder[] sourceFolders = contentEntry.getSourceFolders();
        if (reverseAllSets) {
          sourceFolders = ArrayUtil.reverseArray(sourceFolders);
        }
        for (SourceFolder sourceFolder : sourceFolders) {
          VirtualFile dir = sourceFolder.getFile();
          if (dir != null) {
            fillMapWithModuleSource(module, contentRoot, dir, sourceFolder.getPackagePrefix(), dir, sourceFolder.isTestSource(), progress);
          }
        }
      }
    }

    protected void fillMapWithModuleSource(@NotNull final Module module,
                                           @NotNull final VirtualFile contentRoot,
                                           @NotNull final VirtualFile dir,
                                           @NotNull final String packageName,
                                           @NotNull final VirtualFile sourceRoot,
                                           final boolean isTestSource,
                                           @Nullable final ProgressIndicator progress) {
      assert VfsUtilCore.isAncestor(sourceRoot, dir, false) : "SourceRoot: "+sourceRoot+" ("+sourceRoot.getFileSystem()+"); dir: "+dir+" ("+dir.getFileSystem()+")";
      VfsUtilCore.visitChildrenRecursively(dir, new DirectoryVisitor() {
        private final Stack<String> myPackages = new Stack<String>();

        @Override
        protected DirectoryInfo updateInfo(@NotNull VirtualFile file) {
          if (progress != null) {
            progress.checkCanceled();
          }
          int id = getId(file);
          DirectoryInfo info = getInfo(id);
          if (info == null) return null;
          if (!module.equals(info.getModule())) return null;
          if (!contentRoot.equals(info.getContentRoot())) return null;

          if (info.isInModuleSource()) { // module sources overlap
            String definedPackage = myDirToPackageName.get(id);
            if (definedPackage != null && definedPackage.isEmpty()) return null; // another source root starts here
          }


          assert VfsUtilCore.isAncestor(dir, file, false) : "dir: "+dir+" ("+dir.getFileSystem()+"); file: "+file+" ("+file.getFileSystem()+")";

          int flag = info.getSourceFlag() | DirectoryInfo.MODULE_SOURCE_FLAG;
          flag = BitUtil.set(flag, DirectoryInfo.TEST_SOURCE_FLAG, isTestSource);
          info = with(id, info, null, null, sourceRoot, null, (byte)flag, null);

          String currentPackage = myPackages.isEmpty() ? packageName : getPackageNameForSubdir(myPackages.peek(), file.getName());
          myPackages.push(currentPackage);
          setPackageName(id, currentPackage);
          return info;
        }

        @Override
        protected void afterChildrenVisited(@NotNull VirtualFile file, @NotNull DirectoryInfo info) {
          super.afterChildrenVisited(file, info);
          myPackages.pop();
        }
      });
    }

    private void initLibrarySources(Module module, ProgressIndicator progress) {
      progress.checkCanceled();
      progress.setText2(ProjectBundle.message("project.index.processing.library.sources.progress", module.getName()));

      for (OrderEntry orderEntry : getOrderEntries(module)) {
        if (orderEntry instanceof LibraryOrSdkOrderEntry) {
          VirtualFile[] sourceRoots = ((LibraryOrSdkOrderEntry)orderEntry).getRootFiles(OrderRootType.SOURCES);
          for (final VirtualFile sourceRoot : sourceRoots) {
            fillMapWithLibrarySources(sourceRoot, "", sourceRoot, progress);
          }
        }
      }
    }

    protected void fillMapWithLibrarySources(@NotNull final VirtualFile dir,
                                             @Nullable final String packageName,
                                             @NotNull final VirtualFile sourceRoot,
                                             @Nullable final ProgressIndicator progress) {
      VfsUtilCore.visitChildrenRecursively(dir, new VirtualFileVisitor<String>() {
        { setValueForChildren(packageName); }

        @Override
        public boolean visitFile(@NotNull VirtualFile file) {
          if (progress != null) progress.checkCanceled();
          int dirId = getId(file);
          if (!file.isDirectory() && dirId != getId(dir)|| isIgnored(file)) return false;
          DirectoryInfo info = getOrCreateDirInfo(dirId);

          if (info.isInLibrarySource()) { // library sources overlap
            String definedPackage = myDirToPackageName.get(dirId);
            if (definedPackage != null && definedPackage.isEmpty()) return false; // another library source root starts here
          }

          int flag = info.getSourceFlag() | DirectoryInfo.LIBRARY_SOURCE_FLAG;
          with(dirId, info, null, null, sourceRoot, null, (byte)flag, null);

          final String packageName = getCurrentValue();
          final String newPackageName = Comparing.equal(file, dir) ? packageName : getPackageNameForSubdir(packageName, file.getName());
          setPackageName(dirId, newPackageName);
          setValueForChildren(newPackageName);

          return true;
        }
      });
    }

    private void initLibraryClasses(Module module, ProgressIndicator progress) {
      progress.checkCanceled();
      progress.setText2(ProjectBundle.message("project.index.processing.library.classes.progress", module.getName()));

      for (OrderEntry orderEntry : getOrderEntries(module)) {
        if (orderEntry instanceof LibraryOrSdkOrderEntry) {
          VirtualFile[] classRoots = ((LibraryOrSdkOrderEntry)orderEntry).getRootFiles(OrderRootType.CLASSES);
          for (final VirtualFile classRoot : classRoots) {
            fillMapWithLibraryClasses(classRoot, "", classRoot, progress);
          }
        }
      }
    }

    protected void fillMapWithLibraryClasses(@NotNull final VirtualFile dir,
                                             @NotNull final String packageName,
                                             @NotNull final VirtualFile classRoot,
                                             @Nullable final ProgressIndicator progress) {
      VfsUtilCore.visitChildrenRecursively(dir, new VirtualFileVisitor<String>() {
        { setValueForChildren(packageName); }

        @Override
        public boolean visitFile(@NotNull VirtualFile file) {
          if (progress != null) progress.checkCanceled();
          if (!file.isDirectory() && !Comparing.equal(file, dir) || isIgnored(file)) return false;

          int dirId = getId(file);
          DirectoryInfo info = getOrCreateDirInfo(dirId);

          if (info.hasLibraryClassRoot()) { // library classes overlap
            String definedPackage = myDirToPackageName.get(dirId);
            if (definedPackage != null && definedPackage.isEmpty()) return false; // another library root starts here
          }

          info = with(dirId, info, null, null, null, classRoot, 0, null);

          final String packageName = getCurrentValue();
          final String childPackageName = Comparing.equal(file, dir) ? packageName : getPackageNameForSubdir(packageName, file.getName());
          if (!info.isInModuleSource() && !info.isInLibrarySource()) {
            setPackageName(dirId, childPackageName);
          }
          setValueForChildren(childPackageName);

          return true;
        }
      });
    }

    private void initOrderEntries(@NotNull Module module,
                                  @NotNull MultiMap<VirtualFile, OrderEntry> depEntries,
                                  @NotNull MultiMap<VirtualFile, OrderEntry> libClassRootEntries,
                                  @NotNull MultiMap<VirtualFile, OrderEntry> libSourceRootEntries,
                                  @NotNull ProgressIndicator progress) {
      for (OrderEntry orderEntry : getOrderEntries(module)) {
        if (orderEntry instanceof ModuleOrderEntry) {
          final Module depModule = ((ModuleOrderEntry)orderEntry).getModule();
          if (depModule != null) {
            VirtualFile[] importedClassRoots =
              OrderEnumerator.orderEntries(depModule).exportedOnly().recursively().classes().usingCache().getRoots();
            for (VirtualFile importedClassRoot : importedClassRoots) {
              depEntries.putValue(importedClassRoot, orderEntry);
            }
          }
          VirtualFile[] sourceRoots = orderEntry.getFiles(OrderRootType.SOURCES);
          for (VirtualFile sourceRoot : sourceRoots) {
            depEntries.putValue(sourceRoot, orderEntry);
          }
        }
        else if (orderEntry instanceof ModuleSourceOrderEntry) {
          OrderEntry[] oneEntryList = {orderEntry};
          Module entryModule = orderEntry.getOwnerModule();

          VirtualFile[] sourceRoots = ((ModuleSourceOrderEntry)orderEntry).getRootModel().getSourceRoots();
          for (VirtualFile sourceRoot : sourceRoots) {
            fillMapWithOrderEntries(sourceRoot, oneEntryList, entryModule, null, null, null, progress);
          }
        }
        else if (orderEntry instanceof LibraryOrSdkOrderEntry) {
          final LibraryOrSdkOrderEntry entry = (LibraryOrSdkOrderEntry)orderEntry;
          VirtualFile[] classRoots = entry.getRootFiles(OrderRootType.CLASSES);
          for (VirtualFile classRoot : classRoots) {
            libClassRootEntries.putValue(classRoot, orderEntry);
          }
          VirtualFile[] sourceRoots = entry.getRootFiles(OrderRootType.SOURCES);
          for (VirtualFile sourceRoot : sourceRoots) {
            libSourceRootEntries.putValue(sourceRoot, orderEntry);
          }
        }
      }
    }

    private void fillMapWithOrderEntries(@NotNull MultiMap<VirtualFile, OrderEntry> depEntries,
                                         @NotNull MultiMap<VirtualFile, OrderEntry> libClassRootEntries,
                                         @NotNull MultiMap<VirtualFile, OrderEntry> libSourceRootEntries,
                                         @NotNull ProgressIndicator progress) {
      for (Map.Entry<VirtualFile, Collection<OrderEntry>> mapEntry : depEntries.entrySet()) {
        VirtualFile vRoot = mapEntry.getKey();
        Collection<OrderEntry> entries = mapEntry.getValue();
        fillMapWithOrderEntries(vRoot, toSortedArray(entries), null, null, null, null, progress);
      }

      for (Map.Entry<VirtualFile, Collection<OrderEntry>> mapEntry : libClassRootEntries.entrySet()) {
        final VirtualFile vRoot = mapEntry.getKey();
        final Collection<OrderEntry> entries = mapEntry.getValue();
        fillMapWithOrderEntries(vRoot, toSortedArray(entries), null, vRoot, null, null, progress);
      }

      for (Map.Entry<VirtualFile, Collection<OrderEntry>> mapEntry : libSourceRootEntries.entrySet()) {
        final VirtualFile vRoot = mapEntry.getKey();
        final Collection<OrderEntry> entries = mapEntry.getValue();
        fillMapWithOrderEntries(vRoot, toSortedArray(entries), null, null, vRoot, null, progress);
      }
    }

    protected void setPackageName(int dirId, @Nullable String newPackageName) {
      String oldPackageName = myDirToPackageName.get(dirId);
      if (oldPackageName != null) {
        removeDirFromPackage(oldPackageName, dirId);
      }

      if (newPackageName == null) {
        myDirToPackageName.remove(dirId);
      }
      else {
        addDirToPackage(newPackageName, dirId);

        myDirToPackageName.put(dirId, newPackageName);
      }
    }

    // orderEntries must be sorted BY_OWNER_MODULE
    protected void fillMapWithOrderEntries(@NotNull VirtualFile root,
                                           @NotNull final OrderEntry[] orderEntries,
                                           @Nullable final Module module,
                                           @Nullable final VirtualFile libraryClassRoot,
                                           @Nullable final VirtualFile librarySourceRoot,
                                           @Nullable final DirectoryInfo parentInfo,
                                           @Nullable final ProgressIndicator progress) {
      VfsUtilCore.visitChildrenRecursively(root, new DirectoryVisitor() {
        private final Stack<OrderEntry[]> myEntries = new Stack<OrderEntry[]>();

        @Override
        protected DirectoryInfo updateInfo(@NotNull VirtualFile dir) {
          if (progress != null) {
            progress.checkCanceled();
          }
          if (isIgnored(dir)) return null;

          int dirId = getId(dir);
          DirectoryInfo info = getInfo(dirId); // do not create it here!
          if (info == null) return null;

          if (module != null) {
            if (info.getModule() != module) return null;
            if (!info.isInModuleSource()) return null;
          }
          else if (libraryClassRoot != null) {
            if (!libraryClassRoot.equals(info.getLibraryClassRoot())) return null;
            if (info.isInModuleSource()) return null;
          }
          else if (librarySourceRoot != null) {
            if (!info.isInLibrarySource()) return null;
            if (!librarySourceRoot.equals(info.getSourceRoot())) return null;
            if (info.hasLibraryClassRoot()) return null;
          }

          OrderEntry[] oldParentEntries = myEntries.isEmpty() ? null : myEntries.peek();
          OrderEntry[] oldEntries = info.getOrderEntries();
          myEntries.push(oldEntries);

          OrderEntry[] newOrderEntries = info.calcNewOrderEntries(orderEntries, parentInfo, oldParentEntries);
          info = with(dirId, info, null, null, null, null, 0, newOrderEntries);

          return info;
        }

        @Override
        protected void afterChildrenVisited(@NotNull VirtualFile file, @NotNull DirectoryInfo info) {
          myEntries.pop();
        }
      });
    }

    protected void doInitialize(boolean reverseAllSets/* for testing order independence*/) {
      assertAncestorsConsistent();
      ProgressIndicator progress = ProgressIndicatorProvider.getGlobalProgressIndicator();
      if (progress == null) progress = new EmptyProgressIndicator();

      progress.pushState();

      progress.checkCanceled();
      progress.setText(ProjectBundle.message("project.index.scanning.files.progress"));

      Module[] modules = ModuleManager.getInstance(myProject).getModules();
      if (reverseAllSets) modules = ArrayUtil.reverseArray(modules);

      initExcludedDirMap(modules, progress);

      for (Module module : modules) {
        initModuleContents(module, reverseAllSets, progress);
      }
      // Important! Because module's contents may overlap,
      // first modules should be marked and only after that sources markup
      // should be added. (src markup depends on module markup)
      for (Module module : modules) {
        initModuleSources(module, reverseAllSets, progress);
        initLibrarySources(module, progress);
        initLibraryClasses(module, progress);
      }

      progress.checkCanceled();
      progress.setText2("");

      assertAncestorsConsistent();
      MultiMap<VirtualFile, OrderEntry> depEntries = new MultiMap<VirtualFile, OrderEntry>();
      MultiMap<VirtualFile, OrderEntry> libClassRootEntries = new MultiMap<VirtualFile, OrderEntry>();
      MultiMap<VirtualFile, OrderEntry> libSourceRootEntries = new MultiMap<VirtualFile, OrderEntry>();
      for (Module module : modules) {
        initOrderEntries(module, depEntries, libClassRootEntries, libSourceRootEntries, progress);
      }
      fillMapWithOrderEntries(depEntries, libClassRootEntries, libSourceRootEntries, progress);

      internDirectoryInfos();
    }

    private void internDirectoryInfos() {
      final Map<DirectoryInfo, DirectoryInfo> diInterner = new THashMap<DirectoryInfo, DirectoryInfo>();
      final Map<OrderEntry[], OrderEntry[]> oeInterner = new THashMap<OrderEntry[], OrderEntry[]>(new TObjectHashingStrategy<OrderEntry[]>() {
        @Override
        public int computeHashCode(OrderEntry[] object) {
          return Arrays.hashCode(object);
        }

        @Override
        public boolean equals(OrderEntry[] o1, OrderEntry[] o2) {
          return Arrays.equals(o1, o2);
        }
      });

      assertAncestorsConsistent();
      myDirToInfoMap.transformValues(new TObjectFunction<DirectoryInfo, DirectoryInfo>() {
        @Override
        public DirectoryInfo execute(DirectoryInfo info) {
          DirectoryInfo interned = diInterner.get(info);
          if (interned == null) {
            OrderEntry[] entries = info.getOrderEntries();
            OrderEntry[] internedEntries = oeInterner.get(entries);
            if (internedEntries == null) {
              oeInterner.put(entries, entries);
            }
            else if (internedEntries != entries) {
              info = info.withInternedEntries(internedEntries);
            }
            diInterner.put(info, interned = info);
          }
          return interned;
        }
      });
      assertAncestorsConsistent();
    }

    private void initExcludedDirMap(@NotNull Module[] modules, ProgressIndicator progress) {
      progress.checkCanceled();
      progress.setText2(ProjectBundle.message("project.index.building.exclude.roots.progress"));

      // exclude roots should be merged to prevent including excluded dirs of an inner module into the outer
      // exclude root should exclude from its content root and all outer content roots

      for (Module module : modules) {
        for (ContentEntry contentEntry : getContentEntries(module)) {
          VirtualFile contentRoot = contentEntry.getFile();
          if (contentRoot == null) continue;

          ExcludeFolder[] excludeRoots = contentEntry.getExcludeFolders();
          for (ExcludeFolder excludeRoot : excludeRoots) {
            // Output paths should be excluded (if marked as such) regardless if they're under corresponding module's content root
            VirtualFile excludeRootFile = excludeRoot.getFile();
            if (excludeRootFile != null) {
              if (!FileUtil.startsWith(contentRoot.getUrl(), excludeRoot.getUrl())) {
                if (isExcludeRootForModule(module, excludeRootFile)) {
                  putForFileAndAllAncestors(excludeRootFile, excludeRoot.getUrl());
                }
                myProjectExcludeRoots.add(getId(excludeRootFile));
              }
            }

            putForFileAndAllAncestors(contentRoot, excludeRoot.getUrl());
          }
        }
      }

      for (DirectoryIndexExcludePolicy policy : myExcludePolicies) {
        for (VirtualFile file : policy.getExcludeRootsForProject()) {
          putForFileAndAllAncestors(file, file.getUrl());
          myProjectExcludeRoots.add(getId(file));
        }
      }
    }

    private void putForFileAndAllAncestors(VirtualFile file, String value) {
      TIntObjectHashMap<Set<String>> map = myExcludeRootsMap;
      while (file != null) {
        int id = getId(file);
        Set<String> set = map.get(id);
        if (set == null) {
          set = new THashSet<String>();
          map.put(id, set);
        }
        set.add(value);

        file = file.getParent();
      }
    }

    @NotNull
    IndexState copy(@Nullable final TIntProcedure idFilter) {
      final IndexState copy = new IndexState();

      myExcludeRootsMap.forEachEntry(new TIntObjectProcedure<Set<String>>() {
        @Override
        public boolean execute(int id, Set<String> urls) {
          if (idFilter == null || idFilter.execute(id)) {
            copy.myExcludeRootsMap.put(id, new THashSet<String>(urls));
          }
          return true;
        }
      });

      copy.myProjectExcludeRoots.addAll(myProjectExcludeRoots.toArray());
      myDirToInfoMap.forEachEntry(new TIntObjectProcedure<DirectoryInfo>() {
        @Override
        public boolean execute(int id, DirectoryInfo info) {
          if (idFilter == null || idFilter.execute(id)) {
            copy.storeInfo(info, id);
          }
          return true;
        }
      });


      copy.multiDirPackages.clear();
      for (int[] dirs : multiDirPackages) {
        int[] filtered = ContainerUtil.filter(dirs, new TIntProcedure() {
          @Override
          public boolean execute(int id) {
            return id == -1 || copy.getInfo(id) != null && (idFilter == null || idFilter.execute(id));
          }
        });
        copy.multiDirPackages.add(filtered);
      }
      myPackageNameToDirsMap.forEachEntry(new TObjectIntProcedure<String>() {
        @Override
        public boolean execute(String name, int id) {
          if (id > 0) {
            if (copy.getInfo(id) == null) id = 0;
          }
          else if (id < 0) {
            if (copy.multiDirPackages.get(-id).length == 0) id = 0;
          }
          if (id != 0 && (idFilter == null || idFilter.execute(id))) {
            copy.myPackageNameToDirsMap.put(name, id);
          }
          return true;
        }
      });

      myDirToPackageName.forEachEntry(new TIntObjectProcedure<String>() {
        @Override
        public boolean execute(int id, String name) {
          if (idFilter == null || idFilter.execute(id)) {
            copy.myDirToPackageName.put(id, name);
          }
          return true;
        }
      });

      return copy;
    }
  }

  @NotNull
  private static OrderEntry[] toSortedArray(@NotNull Collection<OrderEntry> entries) {
    if (entries.isEmpty()) {
      return OrderEntry.EMPTY_ARRAY;
    }
    OrderEntry[] result = entries.toArray(new OrderEntry[entries.size()]);
    Arrays.sort(result, DirectoryInfo.BY_OWNER_MODULE);
    return result;
  }

  static int getId(@NotNull VirtualFile file) {
    return ((VirtualFileWithId)file).getId();
  }

  static void assertAncestor(@NotNull DirectoryInfo info, @NotNull VirtualFile root, int myId) {
    VirtualFile myFile = findFileById(myId);
    assert myFile.getFileSystem() == root.getFileSystem() : myFile.getFileSystem() +", "+ root.getFileSystem() +"; my file: "+myFile+"; root: "+root + "; "+
                                                            myFile.getParent().getPath().equals(root.getPath());
    assert VfsUtilCore.isAncestor(root, myFile, false) : "my file: "+myFile+" ("+getId(myFile)+")" + myFile.getClass() + " - " +System.identityHashCode(myFile) +
                                                         "; root: "+root +" ("+getId(root)+")" + root.getClass() + " - " +System.identityHashCode(root) +
                                                         "; equalsToParent:"+ (myFile.getParent() == null ? "" : myFile.getParent().getPath()).equals(root.getPath()) +
                                                         "; equalsToRoot:"+ myFile.equals(root) +
                                                         "; equalsToRootPath:"+ myFile.getPath().equals(root.getPath()) +
                                                         "; my contentRoot: "+info.getContentRoot()+"; my sourceRoot: "+info.getSourceRoot()+"; my classRoot: "+info.getLibraryClassRoot();
  }
}