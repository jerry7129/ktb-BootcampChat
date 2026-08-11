import React from 'react';
import { render, waitFor, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatInput from '../ChatInput';

describe('ChatInput', () => {
  it('renders the lazy emoji picker under React 19', async () => {
    const { container, getByLabelText } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    fireEvent.click(getByLabelText('이모티콘'));

    await waitFor(() => {
      expect(container.querySelector('em-emoji-picker')).toBeInTheDocument();
    });
  });

  it('sends a text message with Enter when not composing', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        onSubmit={onSubmit}
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    const input = getByTestId('chat-message-input');
    fireEvent.change(input, { target: { value: 'hello' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });

    expect(onSubmit).toHaveBeenCalledWith({
      type: 'text',
      content: 'hello',
    });
  });

  it('ignores Enter while IME composition is active', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        onSubmit={onSubmit}
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    const input = getByTestId('chat-message-input');
    fireEvent.change(input, { target: { value: '한글' } });
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, {
      key: 'Enter',
      code: 'Enter',
      keyCode: 229,
      which: 229,
    });

    expect(onSubmit).not.toHaveBeenCalled();
    expect(input).toHaveValue('한글');
  });

  it('does not select a mention with Enter while IME composition is active', () => {
    const { getByTestId } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{
          participants: [
            { id: 'user-1', name: '김민수', email: 'kim@example.com' },
          ],
        }}
      />
    );

    const input = getByTestId('chat-message-input');
    fireEvent.change(input, {
      target: {
        value: '@김',
        selectionStart: 2,
      },
    });
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, {
      key: 'Enter',
      code: 'Enter',
      keyCode: 229,
      which: 229,
    });

    expect(input).toHaveValue('@김');
  });
});
