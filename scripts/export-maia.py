#!/usr/bin/env python3
"""Export official Maia-3 checkpoints, compare ONNX and Java encoding to upstream.
Interface references: CSSLab/maia3; redistribution under AGPL-3.0.
No model is downloaded or run by the phone over a network.
"""
from collections import deque
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

import chess
from huggingface_hub import HfApi
import numpy as np
import onnx
import onnxruntime as ort
import torch
from maia3.dataset import tokenize_board, get_historical_tokens
from maia3.model_registry import resolve_checkpoint_path
from maia3.uci import parse_args, load_model
from maia3.utils import get_all_possible_moves, mirror_move

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'app/src/main/assets/maia'
SOURCE = ROOT / 'third_party/Maia3'
CHECKPOINTS = ROOT / 'third_party/Maia3-checkpoints'
OUT.mkdir(parents=True, exist_ok=True)
CHECKPOINTS.mkdir(parents=True, exist_ok=True)
torch.set_num_threads(2)
torch.manual_seed(7)
cases = [
    (chess.STARTING_FEN, []),
    (chess.STARTING_FEN, ['e2e4']),
    (chess.STARTING_FEN, ['e2e4','c7c5','g1f3','d7d6','d2d4','c5d4','f3d4','g8f6','b1c3','a7a6']),
    ('r1bqk2r/pp3ppp/2n5/2bp4/5B2/2NP1N2/PPP3PP/R2QK2R w KQkq - 0 10', ['d1e2']),
    ('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1', ['e1g1']),
    ('4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2', []),
    ('7k/P7/8/8/8/8/8/7K w - - 0 1', []),
    ('7k/8/8/8/8/8/p7/7K b - - 0 1', []),
]
manifest = []
report = {'upstream': subprocess.check_output(['git','-C',str(SOURCE),'rev-parse','HEAD'],text=True).strip(), 'models': []}
vocabulary = {m:i for i,m in enumerate(get_all_possible_moves())}
assert len(vocabulary) == 4352

class PortableRMSNorm(torch.nn.Module):
    """Export decomposition; results are checked against upstream native RMSNorm."""
    def __init__(self, original):
        super().__init__();self.weight=original.weight;self.eps=original.eps
        self.axes=tuple(range(-len(original.normalized_shape),0))
    def forward(self, x):
        eps=self.eps if self.eps is not None else torch.finfo(x.dtype).eps
        return x*torch.rsqrt(x.pow(2).mean(dim=self.axes,keepdim=True)+eps)*self.weight

def portable_norms(root):
    changes=[]
    for name,child in list(root.named_children()):
        if isinstance(child,torch.nn.RMSNorm):
            replacement=PortableRMSNorm(child);setattr(root,name,replacement)
            changes.append((root,name,child,replacement))
        else: changes.extend(portable_norms(child))
    return changes

for size in ('5m', '23m'):
    cfg = parse_args(['--model', f'maia3-{size}', '--device', 'cpu', '--no-use-amp'])
    assert cfg.history == 8 and not cfg.include_time_info
    revision = HfApi().model_info(cfg.model_spec.repo_id).sha
    cfg.checkpoint_path = resolve_checkpoint_path(cfg.model_spec, revision=revision)
    model = load_model(cfg)
    checkpoint = torch.load(cfg.checkpoint_path, map_location='cpu', weights_only=True)
    state = checkpoint.get('model_state_dict', checkpoint)
    model.load_state_dict({k.replace('smolgen','gab'):v for k,v in state.items()}, strict=True)
    norms=portable_norms(model)
    copied = CHECKPOINTS / f'maia3-{size}.pt'
    shutil.copyfile(cfg.checkpoint_path, copied)
    tokens = torch.zeros((1,64,97),dtype=torch.float32)
    elo = torch.tensor([2200],dtype=torch.int64)
    target = OUT / f'maia3-{size}.onnx'
    with torch.inference_mode():
        torch.onnx.export(model,(tokens,elo,elo),str(target),input_names=['tokens','self_elo','opponent_elo'],output_names=['move_logits','value_logits','ponder'],dynamic_axes={name:{0:'batch'} for name in ['tokens','self_elo','opponent_elo','move_logits','value_logits','ponder']},opset_version=17,do_constant_folding=True,dynamo=False)
    onnx.checker.check_model(onnx.load(target))
    session = ort.InferenceSession(str(target), providers=['CPUExecutionProvider'])
    errors = []
    for fen,moves in cases:
        board=chess.Board(fen); history=deque([tokenize_board(board)],maxlen=8)
        for move in moves:
            board.push_uci(move); history.append(tokenize_board(board))
        expected_tokens=get_historical_tokens(history,cfg,0,0,0,0).numpy()
        java=json.loads(subprocess.check_output(['java','-cp','artifacts/core-tests','MaiaInterop',fen,','.join(moves)],cwd=ROOT,text=True))
        np.testing.assert_array_equal(np.array(java['tokens'],dtype=np.float32).reshape(64,97),expected_tokens)
        expected_indices={m.uci():vocabulary[m.uci() if board.turn else mirror_move(m.uci())] for m in board.legal_moves}
        assert java['indices'] == expected_indices, (fen,java['indices'],expected_indices)
        inputs=expected_tokens[None,:,:]
        for level in (2000,2200,2400,2600):
            elos=np.array([level],dtype=np.int64)
            for parent,name,native,portable in norms: setattr(parent,name,native)
            with torch.inference_mode():
                reference=model(torch.from_numpy(inputs),torch.from_numpy(elos),torch.from_numpy(elos))
            for parent,name,native,portable in norms: setattr(parent,name,portable)
            actual=session.run(None,{'tokens':inputs,'self_elo':elos,'opponent_elo':elos})
            for a,b in zip(reference,actual,strict=True):
                err=float(np.max(np.abs(a.detach().numpy()-b)));errors.append(err)
                if not np.all(np.isfinite(b)) or err>1e-4: raise RuntimeError(f'ONNX parity failed for {size}: {err}')
    digest=hashlib.sha256(target.read_bytes()).hexdigest()
    manifest.extend([f'{target.name}.sha256={digest}',f'{target.name}.size={target.stat().st_size}'])
    report['models'].append({'name':target.name,'sha256':digest,'hf_revision':revision,'checkpoint_sha256':hashlib.sha256(copied.read_bytes()).hexdigest(),'max_output_error':max(errors),'parity_cases':len(cases)*4})
    print(f'PASS: {target.name}, {target.stat().st_size} bytes; ONNX/PyTorch max error {max(errors)}; Java tokens/legal move indices match upstream')
(OUT/'models.properties').write_text('\n'.join(manifest)+'\n')
(OUT/'provenance.json').write_text(json.dumps(report,indent=2)+'\n')
