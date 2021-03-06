/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.atomix.primitives.tree;

/**
 * An exception to be thrown when an invalid path is passed to the
 * {@code DocumentTree}.
 */
public class NoSuchDocumentPathException extends DocumentException {
  public NoSuchDocumentPathException() {
  }

  public NoSuchDocumentPathException(String message) {
    super(message);
  }

  public NoSuchDocumentPathException(String message, Throwable cause) {
    super(message, cause);
  }

  public NoSuchDocumentPathException(Throwable cause) {
    super(cause);
  }
}
