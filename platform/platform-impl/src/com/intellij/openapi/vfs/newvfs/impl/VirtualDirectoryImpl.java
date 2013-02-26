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
package com.intellij.openapi.vfs.newvfs.impl;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.impl.ApplicationImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.io.FileAttributes;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem;
import com.intellij.openapi.vfs.newvfs.RefreshQueue;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.persistent.FSRecords;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.util.*;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

/**
 * @author max
 */
public class VirtualDirectoryImpl extends VirtualFileSystemEntry {
  static final VirtualDirectoryImpl NULL_VIRTUAL_FILE = new VirtualDirectoryImpl("*?;%NULL", null, LocalFileSystem.getInstance(), -42, 0) {
    public String toString() {
      return "NULL";
    }
  };

  private final NewVirtualFileSystem myFS;

  // stores child files. The array is logically divided into the two halves:
  // left subarray for storing real files, right subarray for storing fake files with "suspicious" names
  // files in each subarray are sorted according to the compareNameTo() comparator
  private VirtualFileSystemEntry[] myChildren = EMPTY_ARRAY; // guarded by this, either real file or fake file (meaning it's not a real child but suspicious name)

  public VirtualDirectoryImpl(@NonNls @NotNull final String name,
                              @Nullable final VirtualDirectoryImpl parent,
                              @NotNull final NewVirtualFileSystem fs,
                              final int id,
                              @PersistentFS.Attributes final int attributes) {
    super(name, parent, id, attributes);
    myFS = fs;
  }

  @Override
  @NotNull
  public NewVirtualFileSystem getFileSystem() {
    return myFS;
  }

  @Nullable
  private VirtualFileSystemEntry findChild(@NotNull String name,
                                           final boolean doRefresh,
                                           boolean ensureCanonicalName,
                                           @NotNull NewVirtualFileSystem delegate) {
    VirtualFileSystemEntry result = doFindChild(name, ensureCanonicalName, delegate);
    if (result == NULL_VIRTUAL_FILE) {
      result = doRefresh ? createAndFindChildWithEventFire(name, delegate) : null;
    }
    else if (result != null) {
      if (doRefresh && delegate.isDirectory(result) != result.isDirectory()) {
        RefreshQueue.getInstance().refresh(false, false, null, result);
        result = findChild(name, false, ensureCanonicalName, delegate);
      }
    }

    if (result == null) {
      addToSuspiciousNames(name, !delegate.isCaseSensitive());
    }
    return result;
  }

  private synchronized void addToSuspiciousNames(@NotNull final String name, final boolean ignoreCase) {
    if (allChildrenLoaded()) return;
    int index = binSearch(myChildren, 0, myChildren.length, new Comparer() {
      @Override
      public int compareMyKeyTo(@NotNull VirtualFileSystemEntry file) {
        if (!isSuspiciousName(file)) return 1;
        return -file.compareNameTo(name, ignoreCase);
      }
    });
    if (index >= 0) return; // already added
    insertChildAt(new VirtualFileImpl(name, NULL_VIRTUAL_FILE, -42, -1), index, myChildren, ignoreCase);
  }

  @Nullable // null if there can't be a child with this name, NULL_VIRTUAL_FILE
  private synchronized VirtualFileSystemEntry doFindChildInArray(@NotNull Comparer comparer) {
    VirtualFileSystemEntry[] array = myChildren;
    long r = findIndexInBoth(array, comparer);
    int indexInReal = (int)(r >> 32);
    int indexInSuspicious = (int)r;
    if (indexInSuspicious >= 0) return NULL_VIRTUAL_FILE;

    if (indexInReal >= 0) {
      return array[indexInReal];
    }
    return null;
  }

  @Nullable // null if there can't be a child with this name, NULL_VIRTUAL_FILE if cached as absent, the file if found
  private VirtualFileSystemEntry doFindChild(@NotNull String name, boolean ensureCanonicalName, @NotNull NewVirtualFileSystem delegate) {
    if (name.isEmpty()) {
      return null;
    }

    final boolean ignoreCase = !delegate.isCaseSensitive();
    Comparer comparer = getComparer(name, ignoreCase);
    VirtualFileSystemEntry found = doFindChildInArray(comparer);
    if (found != null) return found;

    if (allChildrenLoaded()) {
      return NULL_VIRTUAL_FILE;
    }

    if (ensureCanonicalName) {
      VirtualFile fake = new FakeVirtualFile(this, name);
      name = delegate.getCanonicallyCasedName(fake);
      if (name.isEmpty()) return null;
    }

    synchronized (this) {
      // do not extract getId outside the synchronized block since it will cause a concurrency problem.
      int id = ourPersistence.getId(this, name, delegate);
      if (id <= 0) {
        return null;
      }
      // maybe another doFindChild() sneaked in the middle
      VirtualFileSystemEntry[] array = myChildren;
      long r = findIndexInBoth(array, comparer);
      int indexInReal = (int)(r >> 32);
      int indexInSuspicious = (int)r;
      if (indexInSuspicious >= 0) return NULL_VIRTUAL_FILE;
      // double check
      if (indexInReal >= 0) {
        return array[indexInReal];
      }

      String shorty = new String(name);
      VirtualFileSystemEntry child = createChild(shorty, id, delegate); // So we don't hold whole char[] buffer of a lengthy path

      insertChildAt(child, indexInReal, array, ignoreCase);
      return child;
    }
  }

  @NotNull
  private static Comparer getComparer(@NotNull final String name, final boolean ignoreCase) {
    return new Comparer() {
      @Override
      public int compareMyKeyTo(@NotNull VirtualFileSystemEntry file) {
        return -file.compareNameTo(name, ignoreCase);
      }
    };
  }

  private synchronized VirtualFileSystemEntry[] getArraySafely() {
    return myChildren;
  }

  @NotNull
  public VirtualFileSystemEntry createChild(@NotNull String name, int id, @NotNull NewVirtualFileSystem delegate) {
    VirtualFileSystemEntry child;

    final int attributes = ourPersistence.getFileAttributes(id);
    if (PersistentFS.isDirectory(attributes)) {
      child = new VirtualDirectoryImpl(name, this, delegate, id, attributes);
    }
    else {
      child = new VirtualFileImpl(name, this, id, attributes);
      //noinspection TestOnlyProblems
      assertAccessInTests(child, delegate);
    }

    if (delegate.markNewFilesAsDirty()) {
      child.markDirty();
    }

    return child;
  }


  private static final boolean IS_UNDER_TEAMCITY = System.getProperty("bootstrap.testcases") != null;
  private static final boolean SHOULD_PERFORM_ACCESS_CHECK = System.getenv("NO_FS_ROOTS_ACCESS_CHECK") == null;

  private static final Collection<String> ourAdditionalRoots = new THashSet<String>();

  @TestOnly
  public static void allowRootAccess(@NotNull String... roots) {
    for (String root : roots) {
      ourAdditionalRoots.add(FileUtil.toSystemIndependentName(root));
    }
  }

  @TestOnly
  public static void disallowRootAccess(@NotNull String... roots) {
    for (String root : roots) {
      ourAdditionalRoots.remove(FileUtil.toSystemIndependentName(root));
    }
  }

  @TestOnly
  private static void assertAccessInTests(@NotNull VirtualFileSystemEntry child, @NotNull NewVirtualFileSystem delegate) {
    final Application application = ApplicationManager.getApplication();
    if (IS_UNDER_TEAMCITY &&
        SHOULD_PERFORM_ACCESS_CHECK &&
        application.isUnitTestMode() &&
        application instanceof ApplicationImpl &&
        ((ApplicationImpl)application).isComponentsCreated()) {
      if (delegate != LocalFileSystem.getInstance() && delegate != JarFileSystem.getInstance()) {
        return;
      }
      // root' children are loaded always
      if (child.getParent() == null || child.getParent().getParent() == null) return;

      Set<String> allowed = allowedRoots();
      boolean isUnder = allowed == null;
      if (!isUnder) {
        for (String root : allowed) {
          String childPath = child.getPath();
          if (delegate == JarFileSystem.getInstance()) {
            VirtualFile local = JarFileSystem.getInstance().getVirtualFileForJar(child);
            assert local != null : child;
            childPath = local.getPath();
          }
          if (FileUtil.startsWith(childPath, root)) {
            isUnder = true;
            break;
          }
          if (root.startsWith(JarFileSystem.PROTOCOL_PREFIX)) {
            String rootLocalPath = FileUtil.toSystemIndependentName(PathUtil.toPresentableUrl(root));
            isUnder = FileUtil.startsWith(childPath, rootLocalPath);
            if (isUnder) break;
          }
        }
      }

      if (!isUnder) {
        if (!allowed.isEmpty()) {
          assert false : "File accessed outside allowed roots: " + child + ";\nAllowed roots: " + new ArrayList<String>(allowed);
        }
      }
    }
  }

  // null means we were unable to get roots, so do not check access
  @Nullable
  @TestOnly
  private static Set<String> allowedRoots() {
    if (insideGettingRoots) return null;

    Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
    if (openProjects.length == 0) return null;

    final Set<String> allowed = new THashSet<String>();
    allowed.add(FileUtil.toSystemIndependentName(PathManager.getHomePath()));

    try {
      URL outUrl = Application.class.getResource("/");
      String output = new File(outUrl.toURI()).getParentFile().getParentFile().getPath();
      allowed.add(FileUtil.toSystemIndependentName(output));
    }
    catch (URISyntaxException ignored) { }

    allowed.add(FileUtil.toSystemIndependentName(SystemProperties.getJavaHome()));
    allowed.add(FileUtil.toSystemIndependentName(new File(FileUtil.getTempDirectory()).getParent()));
    allowed.add(FileUtil.toSystemIndependentName(System.getProperty("java.io.tmpdir")));
    allowed.add(FileUtil.toSystemIndependentName(SystemProperties.getUserHome()));

    for (Project project : openProjects) {
      if (!project.isInitialized()) {
        return null; // all is allowed
      }
      for (VirtualFile root : ProjectRootManager.getInstance(project).getContentRoots()) {
        allowed.add(root.getPath());
      }
      for (VirtualFile root : getAllRoots(project)) {
        allowed.add(StringUtil.trimEnd(root.getPath(), JarFileSystem.JAR_SEPARATOR));
      }
      String location = project.getBasePath();
      assert location != null : project;
      allowed.add(FileUtil.toSystemIndependentName(location));
    }

    allowed.addAll(ourAdditionalRoots);

    return allowed;
  }

  private static boolean insideGettingRoots;

  @TestOnly
  private static VirtualFile[] getAllRoots(@NotNull Project project) {
    insideGettingRoots = true;
    final Set<VirtualFile> roots = new THashSet<VirtualFile>();

    final OrderEnumerator enumerator = ProjectRootManager.getInstance(project).orderEntries();
    ContainerUtil.addAll(roots, enumerator.getClassesRoots());
    ContainerUtil.addAll(roots, enumerator.getSourceRoots());

    insideGettingRoots = false;
    return VfsUtilCore.toVirtualFileArray(roots);
  }

  @Nullable
  private VirtualFileSystemEntry createAndFindChildWithEventFire(@NotNull String name, @NotNull NewVirtualFileSystem delegate) {
    final VirtualFile fake = new FakeVirtualFile(this, name);
    final FileAttributes attributes = delegate.getAttributes(fake);
    if (attributes == null) return null;
    final String realName = delegate.getCanonicallyCasedName(fake);
    final VFileCreateEvent event = new VFileCreateEvent(null, this, realName, attributes.isDirectory(), true);
    RefreshQueue.getInstance().processSingleEvent(event);
    return findChild(realName);
  }

  @Override
  @Nullable
  public NewVirtualFile refreshAndFindChild(@NotNull String name) {
    return findChild(name, true, true, getFileSystem());
  }

  private static int findIndexInOneHalf(final VirtualFileSystemEntry[] array,
                                        int start,
                                        int end,
                                        final boolean isSuspicious,
                                        @NotNull final Comparer comparer) {
    return binSearch(array, start, end, new Comparer() {
      @Override
      public int compareMyKeyTo(@NotNull VirtualFileSystemEntry file) {
        if (isSuspicious && !isSuspiciousName(file)) return 1;
        if (!isSuspicious && isSuspiciousName(file)) return -1;
        return comparer.compareMyKeyTo(file);
      }
    });
  }

  // returns two int indices packed into one long. left index is for the real file array half, right is for the suspicious name array
  private static long findIndexInBoth(@NotNull VirtualFileSystemEntry[] array, @NotNull Comparer comparer) {
    int high = array.length - 1;
    if (high == -1) {
      return pack(-1, -1);
    }
    int low = 0;
    boolean startInSuspicious = isSuspiciousName(array[low]);
    boolean endInSuspicious = isSuspiciousName(array[high]);
    if (startInSuspicious == endInSuspicious) {
      int index = findIndexInOneHalf(array, low, high + 1, startInSuspicious, comparer);
      int otherIndex = startInSuspicious ? -1 : -array.length - 1;
      return startInSuspicious ? pack(otherIndex, index) : pack(index, otherIndex);
    }
    boolean suspicious = false;
    int cmp = -1;
    int mid = -1;
    int foundIndex = -1;
    while (low <= high) {
      mid = low + high >>> 1;
      VirtualFileSystemEntry file = array[mid];
      cmp = comparer.compareMyKeyTo(file);
      suspicious = isSuspiciousName(file);
      if (cmp == 0) {
        foundIndex = mid;
        break;
      }
      if ((suspicious || cmp <= 0) && (!suspicious || cmp >= 0)) {
        int indexInSuspicious = findIndexInOneHalf(array, mid + 1, high + 1, true, comparer);
        int indexInReal = findIndexInOneHalf(array, low, mid, false, comparer);
        return pack(indexInReal, indexInSuspicious);
      }

      if (cmp > 0) {
        low = mid + 1;
      }
      else {
        high = mid - 1;
      }
    }

    // key not found.
    if (cmp != 0) foundIndex = -low-1;
    int newStart = suspicious ? low : mid + 1;
    int newEnd = suspicious ? mid + 1 : high + 1;
    int theOtherHalfIndex = newStart < newEnd ? findIndexInOneHalf(array, newStart, newEnd, !suspicious, comparer) : -newStart-1;
    return suspicious ? pack(theOtherHalfIndex, foundIndex) : pack(foundIndex, theOtherHalfIndex);
  }

  private static long pack(int indexInReal, int indexInSuspicious) {
    return (long)indexInReal << 32 | (indexInSuspicious & 0xffffffffL);
  }

  @Override
  @Nullable
  public synchronized NewVirtualFile findChildIfCached(@NotNull String name) {
    final boolean ignoreCase = !getFileSystem().isCaseSensitive();
    Comparer comparer = getComparer(name, ignoreCase);
    VirtualFileSystemEntry found = doFindChildInArray(comparer);
    return found == NULL_VIRTUAL_FILE ? null : found;
  }

  @Override
  @NotNull
  public Iterable<VirtualFile> iterInDbChildren() {
    if (!ourPersistence.wereChildrenAccessed(this)) {
      return Collections.emptyList();
    }

    if (!ourPersistence.areChildrenLoaded(this)) {
      final String[] names = ourPersistence.listPersisted(this);
      final NewVirtualFileSystem delegate = PersistentFS.replaceWithNativeFS(getFileSystem());
      for (String name : names) {
        findChild(name, false, false, delegate);
      }
    }
    return getCachedChildren();
  }

  @Override
  @NotNull
  public synchronized VirtualFile[] getChildren() {
    VirtualFileSystemEntry[] children = myChildren;
    NewVirtualFileSystem delegate = getFileSystem();
    final boolean ignoreCase = !delegate.isCaseSensitive();
    if (allChildrenLoaded()) {
      assertConsistency(children, ignoreCase);
      int sas = getSuspiciousArrayStart();
      return sas == children.length ? children : Arrays.copyOf(children, sas);
    }

    FSRecords.NameId[] childrenIds = ourPersistence.listAll(this);
    VirtualFileSystemEntry[] result;
    if (childrenIds.length == 0) {
      result = EMPTY_ARRAY;
    }
    else {
      Arrays.sort(childrenIds, new Comparator<FSRecords.NameId>() {
        @Override
        public int compare(FSRecords.NameId o1, FSRecords.NameId o2) {
          String name1 = o1.name;
          String name2 = o2.name;
          return compareNames(name1, name2, ignoreCase);
        }
      });
      result = new VirtualFileSystemEntry[childrenIds.length];
      int delegateI = 0;
      int cachedI = 0;

      int cachedEnd = getSuspiciousArrayStart();
      while (delegateI < childrenIds.length) {
        FSRecords.NameId nameId = childrenIds[delegateI];
        while (cachedI < cachedEnd && children[cachedI].compareNameTo(nameId.name, ignoreCase) < 0) cachedI++;

        VirtualFileSystemEntry resultFile;
        if (cachedI < cachedEnd && children[cachedI].compareNameTo(nameId.name, ignoreCase) == 0) {
          resultFile = children[cachedI++];
        }
        else {
          resultFile = createChild(nameId.name, nameId.id, delegate);
        }
        result[delegateI++] = resultFile;
      }

      assertConsistency(result, ignoreCase);
    }

    if (getId() > 0) {
      myChildren = result;
      setChildrenLoaded();
    }

    return result;
  }

  private static void assertConsistency(@NotNull VirtualFileSystemEntry[] array, boolean ignoreCase) {
    for (int i = 0; i < array.length; i++) {
      VirtualFileSystemEntry file = array[i];
      if (isSuspiciousName(file) && i != array.length - 1 ) {
        assert isSuspiciousName(array[i + 1]);
      }
      if (i != 0) {
        String prevName = array[i - 1].getName();
        int cmp = file.compareNameTo(prevName, ignoreCase);
        assert cmp != 0 : prevName + " equals to "+ file+"; children: "+Arrays.toString(array);

        if (isSuspiciousName(file) == isSuspiciousName(array[i - 1])) {
          assert cmp > 0 : "Not sorted";
        }
      }
    }
  }

  @Override
  @Nullable
  public VirtualFileSystemEntry findChild(@NotNull final String name) {
    return findChild(name, false, true, getFileSystem());
  }

  public VirtualFileSystemEntry findChildById(int id, boolean cachedOnly) {
    VirtualFile[] array = getArraySafely();
    VirtualFileSystemEntry result = null;
    for (VirtualFile file : array) {
      VirtualFileSystemEntry withId = (VirtualFileSystemEntry)file;
      if (withId.getId() == id) {
        result = withId;
        break;
      }
    }
    if (result != null) return result;
    if (cachedOnly) return null;

    String name = ourPersistence.getName(id);
    return findChild(name, false, false, getFileSystem());
  }

  @NotNull
  @Override
  public byte[] contentsToByteArray() throws IOException {
    throw new IOException("Cannot get content of directory: " + this);
  }

  public synchronized void addChild(@NotNull VirtualFileSystemEntry child) {
    VirtualFileSystemEntry[] array = myChildren;
    final String childName = child.getName();
    final boolean ignoreCase = !getFileSystem().isCaseSensitive();
    long r = findIndexInBoth(array, getComparer(childName, ignoreCase));
    int indexInReal = (int)(r >> 32);
    int indexInSuspicious = (int)r;

    if (indexInSuspicious >= 0) {
      // remove suspicious first
      myChildren = array = ArrayUtil.remove(array, indexInSuspicious, new ArrayFactory<VirtualFileSystemEntry>() {
        @Override
        public VirtualFileSystemEntry[] create(int count) {
          return new VirtualFileSystemEntry[count];
        }
      });
      assertConsistency(myChildren, ignoreCase);
    }
    if (indexInReal >= 0) return; // already stored

    insertChildAt(child, indexInReal, array, ignoreCase);
  }

  private void insertChildAt(@NotNull VirtualFileSystemEntry file, int negativeIndex, @NotNull VirtualFileSystemEntry[] array, boolean ignoreCase) {
    VirtualFileSystemEntry[] appended = new VirtualFileSystemEntry[array.length + 1];
    int i = -negativeIndex -1;
    System.arraycopy(array, 0, appended, 0, i);
    appended[i] = file;
    System.arraycopy(array, i, appended, i+1, array.length - i);
    assertConsistency(appended, ignoreCase);
    myChildren = appended;
  }

  public synchronized void removeChild(@NotNull VirtualFile file) {
    boolean ignoreCase = !getFileSystem().isCaseSensitive();
    String name = file.getName();

    myChildren = ArrayUtil.remove(myChildren, (VirtualFileSystemEntry)file, new ArrayFactory<VirtualFileSystemEntry>() {
      @Override
      public VirtualFileSystemEntry[] create(int count) {
        return new VirtualFileSystemEntry[count];
      }
    });
    addToSuspiciousNames(name, ignoreCase);
    assertConsistency(myChildren, ignoreCase);
  }

  private static final int CHILDREN_CACHED = 0x08;
  public synchronized boolean allChildrenLoaded() {
    return getFlag(CHILDREN_CACHED);
  }
  private void setChildrenLoaded() {
    setFlag(CHILDREN_CACHED, true);
  }

  @NotNull
  public synchronized List<String> getSuspiciousNames() {
    List<VirtualFile> suspicious = new SubList<VirtualFile>(myChildren, getSuspiciousArrayStart(), myChildren.length);
    return ContainerUtil.map2List(suspicious, new Function<VirtualFile, String>() {
      @Override
      public String fun(VirtualFile file) {
        return file.getName();
      }
    });
  }

  private int getSuspiciousArrayStart() {
    int index = binSearch(myChildren, 0, myChildren.length, new Comparer() {
      @Override
      public int compareMyKeyTo(@NotNull VirtualFileSystemEntry v) {
        return isSuspiciousName(v) ? -1 : 1;
      }
    });
    return -index - 1;
  }

  private static boolean isSuspiciousName(@NotNull VirtualFileSystemEntry v) {
    return v.getParent() == NULL_VIRTUAL_FILE;
  }

  interface Comparer {
    int compareMyKeyTo(@NotNull VirtualFileSystemEntry file);
  }

  private static int binSearch(@NotNull VirtualFileSystemEntry[] array,
                               int start,
                               int end,
                               @NotNull Comparer comparer) {
    int low = start;
    int high = end - 1;
    assert low >= 0 && low <= array.length;

    while (low <= high) {
      int mid = low + high >>> 1;
      int cmp = comparer.compareMyKeyTo(array[mid]);
      if (cmp > 0) {
        low = mid + 1;
      }
      else if (cmp < 0) {
        high = mid - 1;
      }
      else {
        return mid; // key found
      }
    }
    return -(low + 1);  // key not found.
  }

  @Override
  public boolean isDirectory() {
    return true;
  }

  @Override
  @NotNull
  public synchronized List<VirtualFile> getCachedChildren() {
    return new SubList<VirtualFile>(myChildren, 0, getSuspiciousArrayStart());
  }

  @Override
  public InputStream getInputStream() throws IOException {
    throw new IOException("getInputStream() must not be called against a directory: " + getUrl());
  }

  @Override
  @NotNull
  public OutputStream getOutputStream(final Object requestor, final long newModificationStamp, final long newTimeStamp) throws IOException {
    throw new IOException("getOutputStream() must not be called against a directory: " + getUrl());
  }

  @Override
  public void markDirtyRecursively() {
    markDirty();
    markDirtyRecursivelyInternal();
  }

  // optimisation: do not travel up unnecessary
  private void markDirtyRecursivelyInternal() {
    for (VirtualFileSystemEntry child : getArraySafely()) {
      if (isSuspiciousName(child)) break;
      child.markDirtyInternal();
      if (child instanceof VirtualDirectoryImpl) {
        ((VirtualDirectoryImpl)child).markDirtyRecursivelyInternal();
      }
    }
  }
}
